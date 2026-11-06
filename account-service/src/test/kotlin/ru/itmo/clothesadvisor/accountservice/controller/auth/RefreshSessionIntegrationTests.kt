package ru.itmo.clothesadvisor.accountservice.controller.auth

import com.jayway.jsonpath.JsonPath
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.accountservice.config.TestJwt
import ru.itmo.clothesadvisor.accountservice.model.auth.RefreshTokenState
import ru.itmo.clothesadvisor.accountservice.repository.auth.AuthSessionRepository
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.accountservice.service.auth.PasswordService
import ru.itmo.clothesadvisor.accountservice.service.user.AppUserService

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.auth.access-token-ttl=20m", "app.auth.refresh-token-ttl=3d"],
)
class RefreshSessionIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    @MockitoSpyBean private lateinit var clock: Clock
    @MockitoSpyBean private lateinit var passwords: PasswordService
    @MockitoSpyBean private lateinit var sessions: AuthSessionRepository
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var appUsers: AppUserService
    @Autowired private lateinit var transactionManager: ReactiveTransactionManager
    private val client = HttpClient.newHttpClient()
    private val businessNow = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    private val timeout = Duration.ofSeconds(30)

    @BeforeEach fun businessClock() { doReturn(businessNow).`when`(clock).instant() }
    @AfterEach fun closeClient() = client.close()

    @Test
    fun `login and rotation use configured lifetimes with absolute refresh expiry`() {
        // given
        val id = user()
        val before = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)
        val dbBefore = databaseNow()

        // when
        val login = login()
        val first = token(login)
        val session = jdbc.queryForMap("SELECT * FROM auth_session")

        // then
        assertThat(login.statusCode()).isEqualTo(200)
        assertThat(dto(login).keys).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn")
        assertThat(dto(login)["expiresIn"]).isEqualTo(1200)
        val loginClaims = SignedJWT.parse(dto(login)["accessToken"] as String).jwtClaimsSet
        assertThat(loginClaims.expirationTime.time - loginClaims.issueTime.time).isEqualTo(1_200_000)
        assertThat(first).matches("[A-Za-z0-9_-]{43}")
        assertThat(login.headers().firstValue("Set-Cookie").orElseThrow())
            .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=259200")
            .doesNotContain("Domain=")
        assertThat(login.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat((session["expires_at"] as Timestamp).toInstant()).isEqualTo(businessNow.plus(3, ChronoUnit.DAYS))
        assertThat((session["created_at"] as Timestamp).toInstant()).isBetween(dbBefore, databaseNow())
        val stored = jdbc.queryForMap("SELECT * FROM auth_refresh_token")
        assertThat(stored["token_hash"]).isEqualTo(hash(first))
        assertThat(stored["created_at"]).isEqualTo(session["created_at"])
        assertThat(stored["used_at"]).isNull()
        assertThat(stored.keys).containsExactlyInAnyOrder("token_hash", "session_id", "created_at", "used_at")
        assertThat(stored.toString()).doesNotContain(first)
        val loaded = sessions.findToken(hash(first)).block(timeout)!!
        assertThat(loaded.userId).isEqualTo(id)
        assertThat(loaded.sessionId).isEqualTo(session["id"])
        assertThat(loaded.expiresAt).isEqualTo((session["expires_at"] as Timestamp).toInstant())
        assertThat(loaded.usedAt).isNull()
        assertThat(loaded.revokedAt).isNull()

        // when
        doReturn(businessNow.plus(1, ChronoUnit.DAYS)).`when`(clock).instant()
        val refreshed = post("refresh", first)
        val second = token(refreshed)

        // then
        assertThat(refreshed.statusCode()).isEqualTo(200)
        assertThat(second).isNotEqualTo(first)
        assertThat(refreshed.body()).doesNotContain(first, second, "refreshToken")
        assertThat(dto(refreshed).keys).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn")
        assertThat(dto(refreshed)["expiresIn"]).isEqualTo(1200)
        val refreshedClaims = SignedJWT.parse(dto(refreshed)["accessToken"] as String).jwtClaimsSet
        assertThat(refreshedClaims.expirationTime.time - refreshedClaims.issueTime.time).isEqualTo(1_200_000)
        assertThat(refreshed.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=172800")
        assertThat(jdbc.queryForMap("SELECT * FROM auth_session")).isEqualTo(session)
        assertThat(jdbc.queryForObject("SELECT used_at FROM auth_refresh_token WHERE token_hash = ?",
            Timestamp::class.java, hash(first))!!.toInstant()).isBetween(dbBefore, databaseNow())
        assertThat(jdbc.queryForObject("SELECT used_at FROM auth_refresh_token WHERE token_hash = ?",
            Timestamp::class.java, hash(first))).isEqualTo(jdbc.queryForObject(
            "SELECT created_at FROM auth_refresh_token WHERE token_hash = ?", Timestamp::class.java, hash(second)))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_token", Long::class.java)).isEqualTo(2)
        assertThat(sessions.findToken(hash(first)).block(timeout)!!.usedAt).isEqualTo(
            jdbc.queryForObject("SELECT used_at FROM auth_refresh_token WHERE token_hash = ?",
                Timestamp::class.java, hash(first))!!.toInstant())
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_history", Long::class.java)).isZero()
    }

    @Test
    fun `rotation preserves a stored deadline from a previous refresh lifetime`() {
        // given
        user()
        val first = token(login())
        val originalDeadline = businessNow.plus(7, ChronoUnit.DAYS)
        jdbc.update("UPDATE auth_session SET expires_at = ?", Timestamp.from(originalDeadline))
        doReturn(businessNow.plus(1, ChronoUnit.DAYS)).`when`(clock).instant()

        // when
        val refreshed = post("refresh", first)

        // then
        assertThat(refreshed.statusCode()).isEqualTo(200)
        assertThat(refreshed.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=518400")
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_session", Timestamp::class.java)!!.toInstant())
            .isEqualTo(originalDeadline)
        assertThat(sessions.findToken(hash(token(refreshed))).block(timeout)!!.expiresAt).isEqualTo(originalDeadline)
    }

    @Test
    fun `replaying any retained used token commits family revocation while another session survives`() {
        // given
        user()
        val first = token(login())
        val other = token(login())
        val second = token(post("refresh", first))
        val third = token(post("refresh", second))

        // when
        val replay = post("refresh", first)

        // then
        unauthorized(replay)
        assertThat(jdbc.queryForObject("""SELECT revoked_at FROM auth_session WHERE id =
            (SELECT session_id FROM auth_refresh_token WHERE token_hash = ?)""",
            Timestamp::class.java, hash(first))).isNotNull()
        unauthorized(post("refresh", third))
        unauthorized(post("refresh", "A".repeat(43)))
        assertThat(post("refresh", other).statusCode()).isEqualTo(200)
        assertThat(replay.body()).doesNotContain(first, second, third)
    }

    @Test
    fun `missing unknown malformed expired blocked and deleted sessions fail identically and clear cookie`() {
        // given
        val id = user()
        val live = token(login())
        for (value in listOf(null, "invalid", "A".repeat(43))) {
            // when / then
            unauthorized(post("refresh", value))
        }
        jdbc.update("UPDATE app_user SET status = 'BLOCKED' WHERE id = ?", id)
        unauthorized(post("refresh", live))
        jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", id)
        doReturn(businessNow.plus(3, ChronoUnit.DAYS)).`when`(clock).instant()
        unauthorized(post("refresh", live))
        assertThat(jdbc.queryForObject("SELECT used_at FROM auth_refresh_token", Timestamp::class.java)).isNull()
        jdbc.update("DELETE FROM app_user WHERE id = ?", id)
        unauthorized(post("refresh", live))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_token", Long::class.java)).isZero()
    }

    @Test
    fun `logout by an old token revokes only that session and repeated missing unknown logout is idempotent`() {
        // given
        user()
        val old = token(login())
        val current = token(post("refresh", old))
        val other = token(login())

        // when / then
        for (value in listOf(old, current, old, null, "A".repeat(43))) {
            val response = post("logout", value)
            assertThat(response.statusCode()).isEqualTo(204)
            assertThat(response.body()).isEmpty()
            assertClearedCookie(response)
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        }
        unauthorized(post("refresh", current))
        assertThat(post("refresh", other).statusCode()).isEqualTo(200)
    }

    @Test
    fun `password reset revokes all sessions atomically preserves access JWT and stale version leaves sessions live`() {
        // given
        val id = user()
        val admin = user("admin", "ADMIN")
        val firstLogin = login()
        val first = token(firstLogin)
        val second = token(login())
        val unaffected = token(login("admin"))
        val before = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)

        // when
        val stale = reset(admin, id, 2)

        // then
        assertThat(stale.statusCode()).isEqualTo(409)
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE revoked_at IS NOT NULL", Long::class.java)).isZero()

        // when
        val changed = reset(admin, id, 1)

        // then
        assertThat(changed.statusCode()).isEqualTo(200)
        unauthorized(post("refresh", first))
        unauthorized(post("refresh", second))
        assertThat(post("refresh", unaffected).statusCode()).isEqualTo(200)
        assertThat(send("GET", "/api/auth/me", bearer = dto(firstLogin)["accessToken"] as String).statusCode()).isEqualTo(200)
        assertThat(login().statusCode()).isEqualTo(401)
        assertThat(login(password = NEW_PASSWORD).statusCode()).isEqualTo(200)
        val after = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)
        val history = jdbc.queryForMap("SELECT * FROM app_user_history WHERE user_id = ?", id)
        assertThat(after["version"]).isEqualTo(2L)
        assertThat(history["version"]).isEqualTo(1L)
        assertThat(history["modified_at"]).isEqualTo(before["modified_at"])
        assertThat(jdbc.queryForList("SELECT revoked_at FROM auth_session WHERE user_id = ? AND revoked_at IS NOT NULL", id))
            .hasSize(2).allSatisfy { assertThat(it["revoked_at"]).isEqualTo(after["modified_at"]) }
    }

    @Test
    fun `history failure rolls back password state and keeps all refresh sessions usable`() {
        // given
        val id = user()
        val first = token(login())
        val second = token(login())
        val before = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)
        jdbc.update("""INSERT INTO app_user_history (user_id, version, login, role, status, modified_at)
            SELECT id, version, login, role, status, modified_at FROM app_user WHERE id = ?""", id)
        val beforeSessions = jdbc.queryForList("SELECT * FROM auth_session ORDER BY id")

        // when / then
        StepVerifier.create(appUsers.changePassword(id, 1, "replacement-hash"))
            .expectError(DataIntegrityViolationException::class.java).verify(timeout)
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)).isEqualTo(before)
        assertThat(jdbc.queryForList("SELECT * FROM auth_session ORDER BY id")).isEqualTo(beforeSessions)
        assertThat(post("refresh", first).statusCode()).isEqualTo(200)
        assertThat(post("refresh", second).statusCode()).isEqualTo(200)
    }

    @Test
    fun `failure after revocation rolls back password history and session together`() {
        // given
        val id = user()
        token(login())
        val before = jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)
        val beforeSessions = jdbc.queryForList("SELECT * FROM auth_session")
        doAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val original = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call) as Mono<Int>
            original.then(Mono.error<Int>(DataAccessResourceFailureException("write failed")))
        }.`when`(sessions).revokeAll(id)

        // when / then
        StepVerifier.create(appUsers.changePassword(id, 1, "replacement-hash"))
            .expectError(DataAccessResourceFailureException::class.java).verify(timeout)
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id = ?", id)).isEqualTo(before)
        assertThat(jdbc.queryForList("SELECT * FROM auth_session")).isEqualTo(beforeSessions)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_history", Long::class.java)).isZero()
    }

    @Test
    fun `failed child insertion rolls back token consumption and preserves client cookie`() {
        // given
        user()
        val live = token(login())
        doReturn(Mono.error<Int>(DataAccessResourceFailureException("private $live")))
            .`when`(sessions).insertToken(anyString(), any(UUID::class.java) ?: UUID(0, 0))

        // when
        val response = post("refresh", live)

        // then
        assertThat(response.statusCode()).isEqualTo(503)
        assertThat(response.body()).isEqualTo("""{"error":"service_unavailable"}""")
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty()
        assertThat(jdbc.queryForObject("SELECT used_at FROM auth_refresh_token", Timestamp::class.java)).isNull()
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session", Timestamp::class.java)).isNull()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_token", Long::class.java)).isEqualTo(1)
    }

    @Test
    fun `csrf custom header is mandatory before login refresh or logout and cross origin preflight is denied`() {
        // given
        user()
        val live = token(login())
        val before = jdbc.queryForList("SELECT * FROM auth_refresh_token")
        for (path in listOf("login", "refresh", "logout", "%72efresh", "%6cogout", "%6cogin")) {
            for (header in listOf(null, "0")) {
                // when
                val response = post(path, live, csrf = header)

                // then
                assertThat(response.statusCode()).`as`("POST $path with header $header").isEqualTo(403)
                assertThat(response.body()).isEqualTo("""{"error":"forbidden"}""")
                assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
            }
        }
        val preflight = client.send(HttpRequest.newBuilder(uri("/api/auth/refresh"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "X-Auth-Request").build(), HttpResponse.BodyHandlers.ofString())
        assertThat(preflight.statusCode()).isBetween(400, 499)
        assertThat(preflight.headers().allValues("Access-Control-Allow-Origin")).isEmpty()
        assertThat(jdbc.queryForList("SELECT * FROM auth_refresh_token")).isEqualTo(before)
        assertThat(post("refresh", live).statusCode()).isEqualTo(200)
        unauthorized(post("%72efresh"))
    }

    @Test
    fun `refresh and logout database failures are sanitized unavailable and preserve cookie`() {
        // given
        user()
        val live = token(login())
        doReturn(Mono.error<RefreshTokenState>(DataAccessResourceFailureException("private $live")))
            .`when`(sessions).findToken(anyString())

        for (path in listOf("refresh", "logout")) {
            // when
            val response = post(path, live)

            // then
            assertThat(response.statusCode()).isEqualTo(503)
            assertThat(response.body()).isEqualTo("""{"error":"service_unavailable"}""")
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty()
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        }
    }

    @Test
    fun `two rotations waiting for the user lock issue once then revoke the winning family`() {
        // given
        val id = user()
        val live = token(login())
        val results = competingRequests(id, { post("refresh", live) }, { post("refresh", live) })

        // then
        assertThat(results.map { it.statusCode() }).containsExactlyInAnyOrder(200, 401)
        val winner = token(results.single { it.statusCode() == 200 })
        unauthorized(post("refresh", winner))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_token", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session", Timestamp::class.java)).isNotNull()
    }

    @Test
    fun `concurrent logout and refresh cannot leave an authorized child token`() {
        // given
        val id = user()
        val live = token(login())

        // when
        val results = competingRequests(id, { post("refresh", live) }, { post("logout", live) })

        // then
        assertThat(results[1].statusCode()).isEqualTo(204)
        assertThat(results[0].statusCode()).isIn(200, 401)
        if (results[0].statusCode() == 200) unauthorized(post("refresh", token(results[0])))
        unauthorized(post("refresh", live))
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session", Timestamp::class.java)).isNotNull()
    }

    @Test
    fun `login rechecks verified password after reset without hashing inside a transaction`() {
        // given
        val id = user()
        val admin = user("admin", "ADMIN")
        val verified = CountDownLatch(1)
        val release = Sinks.empty<Void>()
        doAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val original = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call) as Mono<Boolean>
            TransactionSynchronizationManager.forCurrentTransaction().map { true }
                .onErrorReturn(NoTransactionException::class.java, false)
                .flatMap { hasTransaction ->
                    assertThat(hasTransaction).isFalse()
                    original.flatMap { matched ->
                        assertThat(matched).isTrue()
                        verified.countDown()
                        release.asMono().thenReturn(matched)
                    }
                }
        }.`when`(passwords).matches(PASSWORD, passwordHash(id))
        val pending = CompletableFuture.supplyAsync { login() }

        try {
            assertThat(verified.await(20, TimeUnit.SECONDS)).isTrue()
            // when
            assertThat(reset(admin, id, 1).statusCode()).isEqualTo(200)
            release.tryEmitEmpty()

            // then
            assertThat(pending.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(401)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session", Long::class.java)).isZero()
        } finally {
            release.tryEmitEmpty()
            pending.get(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `refresh waiting behind password reset observes the committed revocation`() {
        // given
        val id = user()
        val live = token(login())
        val locked = CountDownLatch(1)
        val release = Sinks.empty<Void>()
        val reset = TransactionalOperator.create(transactionManager).execute {
            users.findByIdForUpdate(id).flatMap {
                locked.countDown()
                release.asMono().then(Mono.defer { appUsers.changePassword(id, 1, "replacement-hash") })
            }
        }.single().toFuture()
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue()
        val refresh = CompletableFuture.supplyAsync { post("refresh", live) }
        try {
            assertBlockedUserLocks(1)

            // when
            release.tryEmitEmpty()
            reset.get(30, TimeUnit.SECONDS)

            // then
            unauthorized(refresh.get(30, TimeUnit.SECONDS))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_token", Long::class.java)).isEqualTo(1)
        } finally {
            release.tryEmitEmpty()
            reset.get(30, TimeUnit.SECONDS)
            refresh.get(30, TimeUnit.SECONDS)
        }
    }

    private fun competingRequests(id: Long, first: () -> HttpResponse<String>, second: () -> HttpResponse<String>):
        List<HttpResponse<String>> = jdbc.dataSource!!.connection.use { lock ->
        lock.autoCommit = false
        lock.prepareStatement("SELECT id FROM app_user WHERE id = ? FOR UPDATE").use {
            it.setLong(1, id)
            it.executeQuery().close()
        }
        val pending = listOf(CompletableFuture.supplyAsync(first), CompletableFuture.supplyAsync(second))
        try {
            assertBlockedUserLocks(2)
            lock.commit()
            pending.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            lock.rollback()
            pending.forEach { it.get(30, TimeUnit.SECONDS) }
        }
    }

    private fun assertBlockedUserLocks(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        do {
            val count = jdbc.queryForObject("""SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE '%app_user%' AND query LIKE '%FOR UPDATE%' AND wait_event_type = 'Lock'""",
                Int::class.java)!!
            if (count >= expected) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("Expected $expected blocked user locks")
    }

    private fun user(login: String = "alice", role: String = "USER"): Long = jdbc.queryForObject(
        """INSERT INTO app_user (login, password_hash, role, status, version)
            VALUES (?, ?, ?::user_role, 'ACTIVE', 1) RETURNING id""", Long::class.java,
        login, passwords.encode(PASSWORD).block(timeout)!!, role,
    )!!

    private fun passwordHash(id: Long) = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String::class.java, id)!!
    private fun databaseNow() = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp::class.java)!!.toInstant()
    private fun hash(raw: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()))
    private fun login(login: String = "alice", password: String = PASSWORD) =
        post("login", body = """{"login":"$login","password":"$password"}""")
    private fun reset(actor: Long, id: Long, version: Long) =
        send("PUT", "/api/admin/users/$id/password", body = """{"password":"$NEW_PASSWORD","version":$version}""",
            bearer = TestJwt.signClaims(TestJwt.claims(actor.toString())
                .issueTime(Date.from(businessNow)).expirationTime(Date.from(businessNow.plusSeconds(900)))))
    private fun post(path: String, cookie: String? = null, body: String? = null, csrf: String? = "1") =
        send("POST", "/api/auth/$path", cookie, body, csrf)
    private fun uri(path: String) = URI("http://127.0.0.1:$port$path")
    private fun send(method: String, path: String, cookie: String? = null, body: String? = null,
        csrf: String? = "1", bearer: String? = null): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(uri(path)).timeout(timeout).apply {
            if (csrf != null) header("X-Auth-Request", csrf)
            if (cookie != null) header("Cookie", "ca_refresh=$cookie")
            if (body != null) header("Content-Type", "application/json")
            if (bearer != null) header("Authorization", "Bearer $bearer")
        }.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build(), HttpResponse.BodyHandlers.ofString(),
    )
    private fun token(response: HttpResponse<String>): String {
        assertThat(response.statusCode()).isEqualTo(200)
        return response.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';').substringAfter('=')
    }
    private fun dto(response: HttpResponse<String>): Map<String, Any?> = JsonPath.read(response.body(), "$")
    private fun unauthorized(response: HttpResponse<String>) {
        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).isEqualTo("""{"error":"unauthorized"}""")
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertClearedCookie(response)
    }
    private fun assertClearedCookie(response: HttpResponse<String>) {
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow())
            .contains("ca_refresh=;", "Max-Age=0", "Path=/api/auth", "Secure", "SameSite=Strict")
            .containsIgnoringCase("HttpOnly")
    }

    companion object {
        private const val PASSWORD = "old-password-123"
        private const val NEW_PASSWORD = "new-password-456"
    }
}
