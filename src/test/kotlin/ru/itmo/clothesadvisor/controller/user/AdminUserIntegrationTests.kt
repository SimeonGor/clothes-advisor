package ru.itmo.clothesadvisor.controller.user

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class AdminUserIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean private lateinit var repository: AppUserRepository
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val passwordHash = "!"

    @AfterEach
    fun closeClientAndCheckStorage() {
        client.close()
        verifyNoInteractions(storage)
    }

    @Test
    fun `list pages all users in descending id order and get exposes exact public fields`() {

        // given
        val admin = user(UserRole.ADMIN)
        val actorId = actorId(admin)
        jdbc.batchUpdate(
            """INSERT INTO app_user (login, password_hash, role, status, version, created_at, modified_at)
                VALUES (?, ?, 'USER', ?::user_status, 1, ?, ?)""",
            (1..51).map {
                arrayOf<Any>(
                    "listed-$it",
                    passwordHash,
                    if (it == 51) "BLOCKED" else "ACTIVE",
                    Timestamp.from(TestTimeConfiguration.FIXED_TIME),
                    Timestamp.from(TestTimeConfiguration.FIXED_TIME),
                )
            },
        )
        val ids = jdbc.queryForList("SELECT id FROM app_user ORDER BY id DESC", Long::class.java)

        // when
        val first = request("GET", PATH, actorId)

        // then
        assertThat(rows(first).map { number(it, "id") }).containsExactlyElementsOf(ids.take(50))

        // then
        assertThat(first.headers().firstValue("X-Total-Count")).hasValue("52")
        rows(first).forEach { assertFields(it) }

        // then
        assertThat(rows(first).first()["status"]).isEqualTo("BLOCKED")
        verify(repository).findAllByOrderByIdDesc(PageRequest.of(0, 50))

        // when
        val next = request("GET", "$PATH?page=1&size=50", actorId)

        // then
        assertThat(rows(next).map { number(it, "id") }).containsExactlyElementsOf(ids.drop(50))

        // then
        assertThat(next.headers().firstValue("X-Total-Count")).hasValue("52")

        // when
        val singleUserPageIds =
            rows(request("GET", "$PATH?page=1&size=1", actorId)).map { number(it, "id") }

        // then
        assertThat(singleUserPageIds).containsExactly(ids[1])

        // when
        val empty = request("GET", "$PATH?page=100", actorId)

        // then
        assertThat(rows(empty)).isEmpty()

        // then
        assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("52")

        // when
        val blocked = request("GET", "$PATH/${ids.first()}", actorId)

        // then
        assertThat(blocked.statusCode()).isEqualTo(200)
        assertFields(dto(blocked))
        assertThat(dto(blocked)["login"]).isEqualTo("listed-51")
        assertThat(dto(blocked)["status"]).isEqualTo("BLOCKED")
        assertThat(number(dto(blocked), "version")).isEqualTo(1)
        assertThat(dto(blocked)["createdAt"]).isEqualTo(TestTimeConfiguration.FIXED_TIME.toString())
        assertThat(dto(blocked)["modifiedAt"])
            .isEqualTo(TestTimeConfiguration.FIXED_TIME.toString())

        // when
        val missingUser = request("GET", "$PATH/${Long.MAX_VALUE}", actorId)

        // then
        assertEmpty(missingUser, 404)
    }

    @Test
    fun `update preserves history and timestamps and rejects stale versions even for no-op`() {

        // given
        val admin = user(UserRole.ADMIN)
        val target = user()
        val actorId = actorId(admin)
        val path = "$PATH/${target.id}"
        val before = TestTimeConfiguration.FIXED_TIME.minusSeconds(1)
        jdbc.update(
            "UPDATE app_user SET created_at = ?, modified_at = ? WHERE id = ?",
            Timestamp.from(before),
            Timestamp.from(before),
            target.id,
        )
        val initial = dto(request("GET", path, actorId))

        // when
        val initialNoOp = request("PUT", path, actorId, update())

        // then
        assertThat(initialNoOp.statusCode()).isEqualTo(200)
        assertThat(dto(initialNoOp)).isEqualTo(initial)
        assertThat(historyCount()).isZero()

        // when
        val changed = request("PUT", path, actorId, update("STYLIST", "BLOCKED"))

        // then
        assertThat(changed.statusCode()).isEqualTo(200)
        assertFields(dto(changed))
        assertThat(dto(changed)["role"]).isEqualTo("STYLIST")
        assertThat(dto(changed)["status"]).isEqualTo("BLOCKED")
        assertThat(number(dto(changed), "version")).isEqualTo(2)
        assertThat(dto(changed)["createdAt"]).isEqualTo(before.toString())
        val modifiedAt =
            jdbc.queryForObject(
                "SELECT modified_at FROM app_user WHERE id = ?",
                Timestamp::class.java,
                target.id,
            )!!
        assertThat(dto(changed)["modifiedAt"]).isEqualTo(modifiedAt.toInstant().toString())
        assertThat(
                jdbc.queryForObject(
                    "SELECT password_hash FROM app_user WHERE id = ?",
                    String::class.java,
                    target.id,
                ),
            )
            .isEqualTo(passwordHash)
        assertThat(
                jdbc.queryForMap(
                    """SELECT user_id, login, role::text, status::text, version, modified_at, archived_at
            FROM app_user_history WHERE user_id = ?""",
                    target.id,
                ),
            )
            .isEqualTo(
                mapOf(
                    "user_id" to target.id,
                    "login" to target.login,
                    "role" to "USER",
                    "status" to "ACTIVE",
                    "version" to 1L,
                    "modified_at" to Timestamp.from(before),
                    "archived_at" to modifiedAt,
                ),
            )

        // when
        val noOp = request("PUT", path, actorId, update("STYLIST", "BLOCKED", 2))

        // then
        assertThat(noOp.statusCode()).isEqualTo(200)
        assertThat(dto(noOp)).isEqualTo(dto(changed))

        // when
        val staleNoOp = request("PUT", path, actorId, update("STYLIST", "BLOCKED", 1))

        // then
        assertEmpty(staleNoOp, 409)

        // when
        val staleUpdate = request("PUT", path, actorId, update("ADMIN", "ACTIVE", 1))

        // then
        assertEmpty(staleUpdate, 409)

        // when
        val missingUpdate = request("PUT", "$PATH/${Long.MAX_VALUE}", actorId, update())

        // then
        assertEmpty(missingUpdate, 404)
        assertThat(historyCount()).isEqualTo(1)

        // when
        val preservedUser = dto(request("GET", path, actorId))

        // then
        assertThat(preservedUser).isEqualTo(dto(changed))

        // when
        val promoted = request("PUT", path, actorId, update("ADMIN", "ACTIVE", 2))

        // then
        assertThat(promoted.statusCode()).isEqualTo(200)
        assertThat(dto(promoted)["role"]).isEqualTo("ADMIN")
        assertThat(dto(promoted)["status"]).isEqualTo("ACTIVE")
        assertThat(number(dto(promoted), "version")).isEqualTo(3)
        assertThat(historyCount()).isEqualTo(2)
    }

    @Test
    fun `updates require active admin and selected actor follows current role and status`() {

        // given
        val admin = user(UserRole.ADMIN)
        val target = user()

        // when: check each actor against the same protected mutation
        assertDenied(null, target, 400)
        for (role in listOf(UserRole.USER, UserRole.STYLIST)) assertDenied(
            actorId(user(role)),
            target,
            403,
        )

        val actorId = actorId(admin)

        // given: change the actor status or role before the next attempt
        users.changeRoleAndStatus(admin.id!!, 1, UserRole.ADMIN, UserStatus.BLOCKED)

        // when
        assertDenied(actorId, target, 403)

        // given
        users.changeRoleAndStatus(admin.id!!, 2, UserRole.USER, UserStatus.ACTIVE)

        // when
        assertDenied(actorId, target, 403)

        // given
        users.changeRoleAndStatus(admin.id!!, 3, UserRole.ADMIN, UserStatus.ACTIVE)

        // when
        val restoredAdminStatus = request("GET", PATH, actorId).statusCode()

        // then
        assertThat(restoredAdminStatus).isEqualTo(200)
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM app_user WHERE id = ?",
                    Long::class.java,
                    target.id,
                ),
            )
            .isEqualTo(1)
    }

    @Test
    fun `invalid pagination and update fields are rejected without changes`() {

        // given
        val admin = user(UserRole.ADMIN)
        val target = user()
        val actorId = actorId(admin)
        for (query in
            listOf(
                "page=-1",
                "size=0",
                "size=51",
                "page=no",
                "size=no",
                "page=2147483647&size=50",
            )) {

            // when
            val invalidPageStatus = request("GET", "$PATH?$query", actorId).statusCode()

            // then
            assertThat(invalidPageStatus).`as`(query).isEqualTo(400)
        }
        for (body in
            listOf(
                "{}",
                """{"status":"ACTIVE","version":1}""",
                """{"role":"USER","version":1}""",
                """{"role":"USER","status":"ACTIVE"}""",
                update(version = 0),
                update(version = -1),
                update(role = "UNKNOWN"),
                update(status = "UNKNOWN"),
                update(role = "user"),
                """{"role":0,"status":"ACTIVE","version":1}""",
                """{"role":"USER","status":0,"version":1}""",
                """{"role":null,"status":"ACTIVE","version":1}""",
                """{"role":"USER","status":null,"version":1}""",
                """{"role":"USER","status":"ACTIVE","version":null}""",
            )) {

            // when
            val invalidUpdateStatus =
                request("PUT", "$PATH/${target.id}", actorId, body).statusCode()

            // then
            assertThat(invalidUpdateStatus).`as`(body).isEqualTo(400)
        }
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM app_user WHERE id = ?",
                    Long::class.java,
                    target.id,
                ),
            )
            .isEqualTo(1)
        assertThat(historyCount()).isZero()
    }

    @Test
    fun `self demotion and blocking conflict with or without another admin while self no-op succeeds`() {

        // given
        val admin = user(UserRole.ADMIN)
        val actorId = actorId(admin)
        val path = "$PATH/${admin.id}"
        repeat(2) { iteration ->

            // given: repeat with another administrator present
            if (iteration == 1) user(UserRole.ADMIN)

            // when
            val selfDemotion = request("PUT", path, actorId, update("USER"))

            // then
            assertEmpty(selfDemotion, 409)

            // when
            val selfBlocking = request("PUT", path, actorId, update("ADMIN", "BLOCKED"))

            // then
            assertEmpty(selfBlocking, 409)

            // when
            val noOp = request("PUT", path, actorId, update("ADMIN"))

            // then
            assertThat(noOp.statusCode()).isEqualTo(200)
            assertThat(number(dto(noOp), "version")).isEqualTo(1)

            // when
            val staleSelfUpdate = request("PUT", path, actorId, update("ADMIN", version = 2))

            // then
            assertEmpty(staleSelfUpdate, 409)
        }
        assertThat(historyCount()).isZero()
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "ADMIN, BLOCKED")
    fun `reciprocal updates retain an active admin and reject actor revoked during lock wait`(
        role: String,
        status: String,
    ) {

        // given
        val waitingAdmin = user(UserRole.ADMIN)
        val winningAdmin = user(UserRole.ADMIN)
        val winnerActorId = actorId(winningAdmin)
        val waiterActorId = actorId(waitingAdmin)
        val locked = CountDownLatch(1)
        val enteringSecondLock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val isFirst = AtomicBoolean(true)
        doAnswer { call ->
                val firstCall = isFirst.compareAndSet(true, false)
                if (!firstCall) enteringSecondLock.countDown()
                val result =
                    mockingDetails(repository).mockCreationSettings.defaultAnswer.answer(call)
                if (firstCall) {
                    locked.countDown()
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
                }
                result
            }
            .`when`(repository)
            .findAllLockedByIdInOrderByIdAsc(anyList())
        Executors.newFixedThreadPool(2).use { executor ->

            // when
            val winner =
                executor.submit(
                    Callable {
                        request(
                            "PUT",
                            "$PATH/${waitingAdmin.id}",
                            winnerActorId,
                            update(role, status),
                        )
                    },
                )
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val waiter =
                    executor.submit(
                        Callable {
                            request(
                                "PUT",
                                "$PATH/${winningAdmin.id}",
                                waiterActorId,
                                update(role, status),
                            )
                        },
                    )
                assertThat(enteringSecondLock.await(10, TimeUnit.SECONDS)).isTrue()
                assertDatabaseLockWait()
                release.countDown()

                // then
                assertThat(winner.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200)
                assertThat(waiter.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(403)
            } finally {
                release.countDown()
            }
        }
        assertThat(
                jdbc.queryForList(
                    "SELECT id FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE'",
                    Long::class.java,
                ),
            )
            .containsExactly(winningAdmin.id)
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM app_user WHERE id = ?",
                    Long::class.java,
                    winningAdmin.id,
                ),
            )
            .isEqualTo(1)
        val changed =
            jdbc.queryForMap(
                "SELECT role::text, status::text FROM app_user WHERE id = ?",
                waitingAdmin.id,
            )
        assertThat(changed["role"]).isEqualTo(role)
        assertThat(changed["status"]).isEqualTo(status)
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM app_user WHERE id = ?",
                    Long::class.java,
                    waitingAdmin.id,
                ),
            )
            .isEqualTo(2)
        assertThat(historyCount()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT user_id FROM app_user_history", Long::class.java))
            .isEqualTo(waitingAdmin.id)
    }

    private fun assertDatabaseLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (
                jdbc.queryForObject(
                    """SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE '%app_user%' AND wait_event_type = 'Lock')""",
                    Boolean::class.java,
                ) == true
            )
                return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError(
            "The second administrative update never waited for a PostgreSQL row lock",
        )
    }

    private fun assertDenied(actorId: String?, target: AppUser, status: Int) {

        // when
        val listStatus = request("GET", PATH, actorId).statusCode()

        // then
        assertThat(listStatus).isEqualTo(200)

        // when
        val readStatus = request("GET", "$PATH/${target.id}", actorId).statusCode()

        // then
        assertThat(readStatus).isEqualTo(200)

        // when
        val updateStatus =
            request("PUT", "$PATH/${target.id}", actorId, update("ADMIN")).statusCode()

        // then
        assertThat(updateStatus).isEqualTo(status)
    }

    private fun user(role: UserRole = UserRole.USER): AppUser =
        jdbc.insertUser(UUID.randomUUID().toString(), passwordHash, role)

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun request(
        method: String,
        path: String,
        actorId: String? = null,
        body: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString)
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
        if (body != null) request.header("Content-Type", "application/json")
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun update(role: String = "USER", status: String = "ACTIVE", version: Long = 1) =
        """{"role":"$role","status":"$status","version":$version}"""

    private fun dto(response: HttpResponse<String>): Map<String, Any?> =
        JsonPath.read(response.body(), "$")

    private fun rows(response: HttpResponse<String>): List<Map<String, Any?>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$")
    }

    private fun number(row: Map<String, Any?>, key: String) = (row.getValue(key) as Number).toLong()

    private fun assertFields(row: Map<String, Any?>) {
        assertThat(row.keys)
            .containsExactlyInAnyOrder(
                "id",
                "login",
                "role",
                "status",
                "version",
                "createdAt",
                "modifiedAt",
            )
    }

    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    private fun historyCount() =
        jdbc.queryForObject("SELECT count(*) FROM app_user_history", Long::class.java)!!

    companion object {
        private const val PATH = "/api/admin/users"
    }
}
