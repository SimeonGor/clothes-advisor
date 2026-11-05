package ru.itmo.clothesadvisor.accountservice.controller.user

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.accountservice.config.TestJwt
import ru.itmo.clothesadvisor.accountservice.dto.user.CreateUserRequest
import ru.itmo.clothesadvisor.accountservice.dto.user.SetUserPasswordRequest
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.service.auth.PasswordService
import ru.itmo.clothesadvisor.accountservice.service.user.UserManagementService

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserManagementIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var management: UserManagementService
    @MockitoSpyBean private lateinit var passwords: PasswordService
    private val client = HttpClient.newHttpClient()

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `created account can log in and password reset archives only public snapshot`() {
        val actor = seed("admin", "ADMIN")
        val created = send("POST", PATH, actor, createBody(" new user ", OLD_PASSWORD))

        assertThat(created.statusCode()).isEqualTo(201)
        val initial = dto(created)
        val id = (initial.getValue("id") as Number).toLong()
        assertThat(created.headers().firstValue("Location")).hasValue("$PATH/$id")
        assertThat(initial.keys).containsExactlyInAnyOrder(
            "id", "login", "role", "status", "version", "createdAt", "modifiedAt",
        )
        assertThat(initial["status"]).isEqualTo("ACTIVE")
        assertThat(initial["role"]).isEqualTo("USER")
        assertThat(initial["version"]).isEqualTo(1)
        val before = row(id)
        val oldHash = before["password_hash"] as String
        assertThat(oldHash).startsWith("{pbkdf2-sha256-600000}").isNotEqualTo(OLD_PASSWORD)
        assertThat(created.body()).doesNotContain(OLD_PASSWORD, oldHash, "password")
        val signedIn = login(" new user ", OLD_PASSWORD)
        assertThat(signedIn.statusCode()).isEqualTo(200)
        val token = dto(signedIn)["accessToken"] as String
        assertThat(login("new user", OLD_PASSWORD).statusCode()).isEqualTo(401)
        assertThat(login(" new user ", OLD_PASSWORD.trim()).statusCode()).isEqualTo(401)

        val reset = send("PUT", "$PATH/$id/password", actor, passwordBody(NEW_PASSWORD, 1))

        assertThat(reset.statusCode()).isEqualTo(200)
        assertThat(dto(reset)["version"]).isEqualTo(2)
        val after = row(id)
        assertThat(after["created_at"]).isEqualTo(before["created_at"])
        assertThat(after["modified_at"]).isNotEqualTo(before["modified_at"])
        assertThat(after["role"]).isEqualTo(before["role"])
        assertThat(after["status"]).isEqualTo(before["status"])
        assertThat(reset.body()).doesNotContain(NEW_PASSWORD, after["password_hash"].toString(), "password")
        val archived = jdbc.queryForMap("SELECT * FROM app_user_history WHERE user_id = ?", id)
        assertThat(archived.keys).containsExactlyInAnyOrder(
            "user_id", "version", "login", "role", "status", "modified_at", "archived_at",
        )
        assertThat(archived["version"]).isEqualTo(1L)
        assertThat(archived["modified_at"]).isEqualTo(before["modified_at"])
        assertThat(archived["archived_at"]).isEqualTo(after["modified_at"])
        assertThat(archived.toString()).doesNotContain(OLD_PASSWORD, NEW_PASSWORD, oldHash)
        assertThat(login(" new user ", OLD_PASSWORD).statusCode()).isEqualTo(401)
        assertThat(login(" new user ", NEW_PASSWORD).statusCode()).isEqualTo(200)
        assertThat(send("GET", "/api/auth/me", token = token).statusCode()).isEqualTo(200)

        val repeated = send("PUT", "$PATH/$id/password", actor, passwordBody(NEW_PASSWORD, 2))
        assertThat(repeated.statusCode()).isEqualTo(200)
        assertThat(row(id)["version"]).isEqualTo(3L)
        assertThat(row(id)["password_hash"]).isNotEqualTo(after["password_hash"])
        assertThat(historyCount()).isEqualTo(2)
        assertThat(send("PUT", "$PATH/$id/password", actor, passwordBody(OLD_PASSWORD, 2))
            .statusCode()).isEqualTo(409)
        assertThat(send("PUT", "$PATH/${Long.MAX_VALUE}/password", actor, passwordBody(NEW_PASSWORD, 1))
            .statusCode()).isEqualTo(404)
        assertThat(historyCount()).isEqualTo(2)
    }

    @Test
    fun `unauthenticated non admin and blocked callers cannot create or reset`() {
        val target = seed("target")
        val before = row(target)
        val actors = listOf(null, seed("user"), seed("stylist", "STYLIST"),
            seed("blocked", "ADMIN", "BLOCKED"))
        for (actor in actors) {
            val expected = if (actor == null) 401 else 403
            assertThat(send("POST", PATH, actor, createBody("denied", NEW_PASSWORD)).statusCode())
                .isEqualTo(expected)
            assertThat(send("PUT", "$PATH/$target/password", actor, passwordBody(NEW_PASSWORD, 1))
                .statusCode()).isEqualTo(expected)
        }
        assertThat(row(target)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE login = 'denied'", Long::class.java))
            .isZero()
        assertThat(historyCount()).isZero()
    }

    @Test
    fun `duplicate login conflicts and malformed credential requests do not expose secrets or write`() {
        val actor = seed("admin", "ADMIN")
        val target = seed("existing")
        val before = row(target)
        assertThat(send("POST", PATH, actor, createBody("existing", NEW_PASSWORD)).statusCode())
            .isEqualTo(409)
        val invalidCreates = listOf(
            "{}", """{"login":"new","password":"$NEW_PASSWORD"}""",
            createBody("", NEW_PASSWORD), createBody("a".repeat(101), NEW_PASSWORD),
            createBody("new", "short-secret".take(11)), createBody("new", " ".repeat(12)),
            createBody("new", "p".repeat(129)),
            """{"login":"new","password":null,"role":"USER"}""",
            createBody("new", NEW_PASSWORD).replace("USER", "UNKNOWN"),
        )
        val invalidResets = listOf(
            "{}", """{"password":"$NEW_PASSWORD"}""",
            passwordBody(NEW_PASSWORD, 0), passwordBody(NEW_PASSWORD, -1),
            """{"password":null,"version":1}""", passwordBody("short-secret".take(11), 1),
        )
        for ((method, path, bodies) in listOf(
            Triple("POST", PATH, invalidCreates), Triple("PUT", "$PATH/$target/password", invalidResets),
        )) {
            for (body in bodies) {
                val response = send(method, path, actor, body)
                assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
                assertThat(response.body()).isEqualTo("""{"error":"invalid_request"}""")
            }
        }
        assertThat(row(target)).isEqualTo(before)
        assertThat(historyCount()).isZero()
        assertThat(CreateUserRequest("new", NEW_PASSWORD, UserRole.USER).toString())
            .doesNotContain(NEW_PASSWORD)
        assertThat(SetUserPasswordRequest(NEW_PASSWORD, 1).toString()).doesNotContain(NEW_PASSWORD)
        val schema = send("GET", "/v3/api-docs").body()
        for (name in listOf("CreateUserRequest", "SetUserPasswordRequest")) {
            assertThat(JsonPath.read<Boolean>(schema,
                "$.components.schemas.$name.properties.password.writeOnly")).isTrue()
        }
    }

    @Test
    fun `history failure rolls back password version and timestamps`() {
        val actor = seed("admin", "ADMIN")
        val target = seed("target")
        val before = row(target)
        jdbc.update("""INSERT INTO app_user_history (user_id, version, login, role, status, modified_at)
            SELECT id, version, login, role, status, modified_at FROM app_user WHERE id = ?""", target)
        val history = jdbc.queryForList("SELECT * FROM app_user_history")

        StepVerifier.create(management.setPassword(actor, target, SetUserPasswordRequest(NEW_PASSWORD, 1)))
            .expectError(DataIntegrityViolationException::class.java).verify()

        assertThat(row(target)).isEqualTo(before)
        assertThat(jdbc.queryForList("SELECT * FROM app_user_history")).isEqualTo(history)
    }

    @Test
    fun `all management hashing is subscribed outside a reactive transaction`() {
        val actor = seed("admin", "ADMIN")
        val checked = AtomicInteger()
        doAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val original = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call) as Mono<String>
            TransactionSynchronizationManager.forCurrentTransaction()
                .map { true }.onErrorReturn(NoTransactionException::class.java, false)
                .flatMap { hasTransaction ->
                    assertThat(hasTransaction).isFalse()
                    checked.incrementAndGet()
                    original
                }
        }.`when`(passwords).encode(anyString())

        val created = management.create(actor, CreateUserRequest("new", NEW_PASSWORD, UserRole.USER))
            .block(Duration.ofSeconds(20))!!
        management.setPassword(actor, created.id, SetUserPasswordRequest(OLD_PASSWORD, 1))
            .block(Duration.ofSeconds(20))
        management.bootstrap("initial", NEW_PASSWORD).block(Duration.ofSeconds(20))

        assertThat(checked).hasValue(3)
    }

    @ParameterizedTest
    @CsvSource("POST, USER, ACTIVE", "POST, ADMIN, BLOCKED", "PUT, USER, ACTIVE", "PUT, ADMIN, BLOCKED")
    fun `credential writes recheck administrator after actual database lock wait`(
        method: String,
        role: String,
        status: String,
    ) {
        val actor = seed("admin", "ADMIN")
        val target = seed("target")
        val before = row(target)
        jdbc.dataSource!!.connection.use { lock ->
            lock.autoCommit = false
            lock.prepareStatement("SELECT id FROM app_user WHERE id = ? FOR UPDATE").use {
                it.setLong(1, actor)
                it.executeQuery().close()
            }
            val pending = CompletableFuture.supplyAsync {
                if (method == "POST") send(method, PATH, actor, createBody("new", NEW_PASSWORD))
                else send(method, "$PATH/$target/password", actor, passwordBody(NEW_PASSWORD, 1))
            }
            try {
                assertLockWait()
                lock.prepareStatement("UPDATE app_user SET role = ?::user_role, status = ?::user_status WHERE id = ?")
                    .use {
                        it.setString(1, role)
                        it.setString(2, status)
                        it.setLong(3, actor)
                        it.executeUpdate()
                    }
                lock.commit()
                assertThat(pending.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(403)
            } finally {
                lock.rollback()
                pending.get(30, TimeUnit.SECONDS)
            }
        }
        assertThat(row(target)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long::class.java)).isEqualTo(2)
        assertThat(historyCount()).isZero()
    }

    private fun assertLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        do {
            if (jdbc.queryForObject("""SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE '%app_user%' AND query LIKE '%FOR UPDATE%' AND wait_event_type = 'Lock')""",
                Boolean::class.java) == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("Credential write did not wait for the administrator row lock")
    }

    private fun seed(login: String, role: String = "USER", status: String = "ACTIVE"): Long =
        jdbc.queryForObject("""INSERT INTO app_user (login, password_hash, role, status, version)
            VALUES (?, '!', ?::user_role, ?::user_status, 1) RETURNING id""",
            Long::class.java, login, role, status)!!

    private fun row(id: Long) = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)
    private fun historyCount() = jdbc.queryForObject("SELECT count(*) FROM app_user_history", Long::class.java)!!
    private fun createBody(login: String, password: String) =
        """{"login":"$login","password":"$password","role":"USER"}"""
    private fun passwordBody(password: String, version: Long) = """{"password":"$password","version":$version}"""
    private fun login(login: String, password: String) = send("POST", "/api/auth/login",
        body = """{"login":"$login","password":"$password"}""")
    private fun dto(response: HttpResponse<String>): Map<String, Any?> = JsonPath.read(response.body(), "$")

    private fun send(
        method: String,
        path: String,
        actor: Long? = null,
        body: String? = null,
        token: String? = actor?.let(TestJwt::sign),
    ): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(30))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .apply {
                if (method == "POST" && path == "/api/auth/login") header("X-Auth-Request", "1")
                if (token != null) header("Authorization", "Bearer $token")
                if (body != null) header("Content-Type", "application/json")
            }.build(), HttpResponse.BodyHandlers.ofString(),
    )

    companion object {
        private const val PATH = "/api/admin/users"
        private const val OLD_PASSWORD = " old password "
        private const val NEW_PASSWORD = " new password "
    }
}
