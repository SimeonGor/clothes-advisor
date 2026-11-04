package ru.itmo.clothesadvisor.accountservice.controller.user

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import ru.itmo.clothesadvisor.accountservice.config.TestJwt
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.accountservice.service.user.AppUserService

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUserIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var users: AppUserService
    @MockitoSpyBean private lateinit var repository: AppUserRepository
    private val client = HttpClient.newHttpClient()

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `OpenAPI declares bearer security without identity headers`() {
        // given
        val path = "$.components.securitySchemes.bearerAuth"

        // when
        val response = send("GET", "/v3/api-docs")

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(JsonPath.read<String>(response.body(), "$path.type")).isEqualTo("http")
        assertThat(JsonPath.read<String>(response.body(), "$path.scheme")).isEqualTo("bearer")
        assertThat(response.body()).doesNotContain("X-User-Id", "passwordHash")
    }

    @Test
    fun `admin list paginates in database and exposes exact fields including blocked users`() {
        // given
        val timestamp = Instant.parse("2026-01-01T01:02:03.123456Z")
        jdbc.batchUpdate(
            """INSERT INTO app_user (login, password_hash, role, status, version,
                created_at, modified_at) VALUES (?, 'private-hash', 'USER', ?::user_status, 1, ?, ?)""",
            (1..52).map {
                arrayOf<Any>("listed-$it", if (it == 52) "BLOCKED" else "ACTIVE",
                    Timestamp.from(timestamp), Timestamp.from(timestamp))
            },
        )
        val actorId = jdbc.queryForObject("SELECT min(id) FROM app_user", Long::class.java)!!
        jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", actorId)
        val actor = actorId.toString()
        val ids = jdbc.queryForList("SELECT id FROM app_user ORDER BY id DESC", Long::class.java)

        // when
        val first = send("GET", PATH, actor)
        val next = send("GET", "$PATH?page=1&size=50", actor)
        val single = send("GET", "$PATH?page=1&size=1", actor)
        val empty = send("GET", "$PATH?page=100", actor)
        val blocked = send("GET", "$PATH/${ids.first()}", actor)

        // then
        assertThat(rows(first).map { number(it, "id") }).containsExactlyElementsOf(ids.take(50))
        assertThat(rows(first).first()["status"]).isEqualTo("BLOCKED")
        assertThat(rows(next).map { number(it, "id") }).containsExactlyElementsOf(ids.drop(50))
        assertThat(rows(single).map { number(it, "id") }).containsExactly(ids[1])
        assertThat(rows(empty)).isEmpty()
        for (response in listOf(first, next, single, empty)) {
            assertThat(response.headers().firstValue("X-Total-Count")).hasValue("52")
            rows(response).forEach { assertFields(it) }
            assertThat(response.body()).doesNotContain("private-hash", "password")
        }
        verify(repository).findPage(50, 0)
        verify(repository).findPage(50, 50)
        assertThat(blocked.statusCode()).isEqualTo(200)
        assertFields(dto(blocked))
        assertThat(dto(blocked)["status"]).isEqualTo("BLOCKED")
        assertThat(dto(blocked)["login"]).isEqualTo("listed-52")
        assertThat(number(dto(blocked), "version")).isEqualTo(1)
        assertThat(dto(blocked)["createdAt"]).isEqualTo(timestamp.toString())
        assertThat(dto(blocked)["modifiedAt"]).isEqualTo(timestamp.toString())
        assertEmpty(send("GET", "$PATH/${Long.MAX_VALUE}", actor), 404)
    }

    @Test
    fun `change archives previous values while no op and stale versions preserve history`() {
        // given
        val actor = user(UserRole.ADMIN)
        val target = user()
        val path = "$PATH/${target.id}"
        val before = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", target.id)
        val initial = dto(send("GET", path, actor.id.toString()))

        // when
        val initialNoOp = send("PUT", path, actor.id.toString(), update())

        // then
        assertThat(initialNoOp.statusCode()).isEqualTo(200)
        assertThat(dto(initialNoOp)).isEqualTo(initial)
        assertThat(historyCount()).isZero()

        // when
        val changed = send("PUT", path, actor.id.toString(), update("STYLIST", "BLOCKED"))

        // then
        assertThat(changed.statusCode()).isEqualTo(200)
        assertFields(dto(changed))
        assertThat(number(dto(changed), "version")).isEqualTo(2)
        assertThat(dto(changed)["role"]).isEqualTo("STYLIST")
        assertThat(dto(changed)["status"]).isEqualTo("BLOCKED")
        assertThat(dto(changed)["createdAt"]).isEqualTo(target.createdAt.toString())
        assertThat(changed.body()).doesNotContain("private-hash", "password")
        val saved = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", target.id)
        assertThat(saved["password_hash"]).isEqualTo(before["password_hash"])
        assertThat(dto(changed)["modifiedAt"])
            .isEqualTo((saved["modified_at"] as Timestamp).toInstant().toString())
        val history = jdbc.queryForMap("SELECT * FROM app_user_history")
        for (column in listOf("login", "role", "status", "version", "modified_at")) {
            assertThat(history[column]).describedAs(column).isEqualTo(before[column])
        }
        assertThat(history["user_id"]).isEqualTo(target.id)
        assertThat(history["archived_at"]).isEqualTo(saved["modified_at"])

        // when
        val noOp = send("PUT", path, actor.id.toString(), update("STYLIST", "BLOCKED", 2))
        val staleNoOp = send("PUT", path, actor.id.toString(), update("STYLIST", "BLOCKED"))
        val staleChange = send("PUT", path, actor.id.toString(), update())

        // then
        assertThat(noOp.statusCode()).isEqualTo(200)
        assertThat(dto(noOp)).isEqualTo(dto(changed))
        assertEmpty(staleNoOp, 409)
        assertEmpty(staleChange, 409)
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", target.id))
            .isEqualTo(saved)
        assertThat(historyCount()).isEqualTo(1)

        // when
        val missing = send("PUT", "$PATH/${Long.MAX_VALUE}", actor.id.toString(), update())

        // then
        assertEmpty(missing, 404)
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", target.id)).isEqualTo(saved)
        assertThat(historyCount()).isEqualTo(1)

        // when
        val promoted = send("PUT", path, actor.id.toString(), update("ADMIN", version = 2))

        // then
        assertThat(promoted.statusCode()).isEqualTo(200)
        assertThat(dto(promoted)["role"]).isEqualTo("ADMIN")
        assertThat(dto(promoted)["status"]).isEqualTo("ACTIVE")
        assertThat(number(dto(promoted), "version")).isEqualTo(3)
        assertThat(historyCount()).isEqualTo(2)
    }

    @Test
    fun `admin reads and updates enforce current role and return sanitized errors`() {
        // given
        val target = user()
        val stylist = user(UserRole.STYLIST)
        val blocked = user(UserRole.ADMIN, UserStatus.BLOCKED)
        val actors = listOf(
            null to 401, "bad" to 401,
            Long.MAX_VALUE.toString() to 401, target.id.toString() to 403,
            stylist.id.toString() to 403, blocked.id.toString() to 403,
        )

        for ((actor, status) in actors) {
            // when
            val list = send("GET", PATH, actor)
            val get = send("GET", "$PATH/${target.id}", actor)
            val change = send("PUT", "$PATH/${target.id}", actor, update("ADMIN"))

            // then
            assertThat(list.statusCode()).isEqualTo(status)
            assertThat(get.statusCode()).isEqualTo(status)
            assertThat(change.statusCode()).describedAs("actor=$actor").isEqualTo(status)
            when (status) {
                401 -> assertThat(change.body()).isEqualTo("""{"error":"unauthorized"}""")
                403 -> assertThat(change.body()).isEqualTo("""{"error":"forbidden"}""")
                404 -> assertThat(change.body()).isEmpty()
            }
        }
        assertThat(historyCount()).isZero()
        assertThat(jdbc.queryForObject("SELECT max(version) FROM app_user", Long::class.java))
            .isEqualTo(1)

        // given
        val refreshedAdmin = user(UserRole.ADMIN)
        val refreshedTarget = user()
        val refreshedActor = refreshedAdmin.id.toString()
        for ((index, roleStatus) in listOf(
            UserRole.ADMIN to UserStatus.BLOCKED,
            UserRole.USER to UserStatus.ACTIVE,
        ).withIndex()) {
            // given
            val (role, status) = roleStatus
            users.changeRoleAndStatus(refreshedAdmin.id, index + 1L, role, status)
                .block(Duration.ofSeconds(10))

            // when
            val response = send(
                "PUT", "$PATH/${refreshedTarget.id}", refreshedActor, update("ADMIN"),
            )

            // then
            assertThat(response.statusCode()).isEqualTo(403)
            assertThat(response.body()).isEqualTo("""{"error":"forbidden"}""")
            assertThat(jdbc.queryForObject(
                "SELECT version FROM app_user WHERE id = ?", Long::class.java, refreshedTarget.id,
            )).isEqualTo(1)
        }

        // when
        users.changeRoleAndStatus(refreshedAdmin.id, 3, UserRole.ADMIN, UserStatus.ACTIVE)
            .block(Duration.ofSeconds(10))

        // then
        assertThat(send("GET", PATH, refreshedActor).statusCode()).isEqualTo(200)
    }

    @Test
    fun `invalid pagination and JSON are rejected without persistence`() {
        // given
        val actor = user(UserRole.ADMIN).id.toString()
        val target = user()
        val queries = listOf("page=-1", "size=0", "size=51", "page=no", "size=no",
            "page=2147483647&size=50", "page=2147483648", "size=2147483648")
        val bodies = listOf(
            "", "{", "null", "{}", """{"status":"ACTIVE","version":1}""",
            """{"role":"USER","version":1}""", """{"role":"USER","status":"ACTIVE"}""",
            update(version = 0), update(version = -1), update(role = "UNKNOWN"),
            update(status = "UNKNOWN"), update(role = "user"), update(status = "active"),
            """{"role":0,"status":"ACTIVE","version":1}""",
            """{"role":"USER","status":0,"version":1}""",
            """{"role":null,"status":"ACTIVE","version":1}""",
            """{"role":"USER","status":null,"version":1}""",
            """{"role":"USER","status":"ACTIVE","version":null}""",
        )

        for (query in queries) {
            // when
            val response = send("GET", "$PATH?$query", actor)

            // then
            assertThat(response.statusCode()).describedAs(query).isEqualTo(400)
        }
        for (body in bodies) {
            // when
            val response = send("PUT", "$PATH/${target.id}", actor, body)

            // then
            assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
            assertThat(response.body()).isEqualTo("""{"error":"invalid_request"}""")
        }
        assertThat(historyCount()).isZero()
        assertThat(jdbc.queryForObject("SELECT max(version) FROM app_user", Long::class.java))
            .isEqualTo(1)
    }

    @Test
    fun `self demotion and blocking conflict with and without another active admin`() {
        // given
        val actor = user(UserRole.ADMIN)
        repeat(2) { iteration ->
            if (iteration == 1) user(UserRole.ADMIN)

            // when
            val demotion = send("PUT", "$PATH/${actor.id}", actor.id.toString(), update())
            val blocking = send("PUT", "$PATH/${actor.id}", actor.id.toString(),
                update("ADMIN", "BLOCKED"))
            val noOp = send("PUT", "$PATH/${actor.id}", actor.id.toString(), update("ADMIN"))
            val stale = send("PUT", "$PATH/${actor.id}", actor.id.toString(),
                update("ADMIN", version = 2))

            // then
            assertEmpty(demotion, 409)
            assertEmpty(blocking, 409)
            assertThat(noOp.statusCode()).isEqualTo(200)
            assertThat(number(dto(noOp), "version")).isEqualTo(1)
            assertEmpty(stale, 409)
        }
        assertThat(historyCount()).isZero()
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "ADMIN, BLOCKED")
    fun `reciprocal updates recheck actor after actual PostgreSQL lock wait`(
        role: String,
        status: String,
    ) {
        // given
        val waitingAdmin = user(UserRole.ADMIN)
        val winningAdmin = user(UserRole.ADMIN)
        val locked = CountDownLatch(1)
        val release = Sinks.empty<Void>()
        val first = AtomicBoolean(true)
        doAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val query = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
                as Flux<AppUser>
            if (first.compareAndSet(true, false)) {
                query.collectList().flatMapMany { rows ->
                    locked.countDown()
                    release.asMono().thenMany(Flux.fromIterable(rows))
                }
            } else query
        }.`when`(repository).findLockedRows(anyLong(), anyLong())

        // when
        val winner = client.sendAsync(request("PUT", "$PATH/${waitingAdmin.id}",
            winningAdmin.id.toString(), update(role, status)), HttpResponse.BodyHandlers.ofString())
        var waiter: CompletableFuture<HttpResponse<String>>? = null
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
            waiter = client.sendAsync(request("PUT", "$PATH/${winningAdmin.id}",
                waitingAdmin.id.toString(), update(role, status)), HttpResponse.BodyHandlers.ofString())
            assertDatabaseLockWait()
            release.tryEmitEmpty().orThrow()

            // then
            assertThat(winner.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200)
            val denied = waiter.get(30, TimeUnit.SECONDS)
            assertThat(denied.statusCode()).isEqualTo(403)
            assertThat(denied.body()).isEqualTo("""{"error":"forbidden"}""")
        } finally {
            release.tryEmitEmpty()
            winner.get(30, TimeUnit.SECONDS)
            waiter?.get(30, TimeUnit.SECONDS)
        }
        assertThat(jdbc.queryForList(
            "SELECT id FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE'", Long::class.java,
        )).containsExactly(winningAdmin.id)
        val changed = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", waitingAdmin.id)
        assertThat(changed["role"].toString()).isEqualTo(role)
        assertThat(changed["status"].toString()).isEqualTo(status)
        assertThat(changed["version"]).isEqualTo(2L)
        assertThat(jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?",
            Long::class.java, winningAdmin.id)).isEqualTo(1)
        assertThat(historyCount()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT user_id FROM app_user_history", Long::class.java))
            .isEqualTo(waitingAdmin.id)
    }

    private fun assertDatabaseLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            if (jdbc.queryForObject(
                """SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                    AND query LIKE '%app_user%' AND query LIKE '%FOR UPDATE%'
                    AND wait_event_type = 'Lock')""", Boolean::class.java,
            ) == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("Administrative update did not wait for a PostgreSQL row lock")
    }

    private fun user(role: UserRole = UserRole.USER, status: UserStatus = UserStatus.ACTIVE) =
        users.create(UUID.randomUUID().toString(), "private-hash", role, status)
            .block(Duration.ofSeconds(20))!!

    private fun request(method: String, path: String, actor: String? = null, body: String? = null) =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(30)).method(method,
                body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .apply {
                if (actor != null) header("Authorization", "Bearer " +
                    (actor.toLongOrNull()?.let(TestJwt::sign) ?: actor))
                if (body != null) header("Content-Type", "application/json")
            }.build()

    private fun send(method: String, path: String, actor: String? = null, body: String? = null) =
        client.send(request(method, path, actor, body), HttpResponse.BodyHandlers.ofString())

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
        assertThat(row.keys).containsExactlyInAnyOrder(
            "id", "login", "role", "status", "version", "createdAt", "modifiedAt",
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
