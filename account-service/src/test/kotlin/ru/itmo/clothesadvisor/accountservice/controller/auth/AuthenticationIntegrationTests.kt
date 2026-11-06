package ru.itmo.clothesadvisor.accountservice.controller.auth

import com.jayway.jsonpath.JsonPath
import com.nimbusds.jose.JWSAlgorithm
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.accountservice.config.TestJwt
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.accountservice.service.auth.PasswordService

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["springdoc.api-docs.enabled=true"],
)
class AuthenticationIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var passwords: PasswordService
    @MockitoSpyBean private lateinit var users: AppUserRepository
    private val client = HttpClient.newHttpClient()

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `real password login issues a fifteen minute bearer and me exposes only identity`() {
        // given
        val login = " login with spaces "
        val password = " password with spaces "
        val hash = passwords.encode(password).block(Duration.ofSeconds(10))!!
        val id = user(login = login, hash = hash)

        // when
        val loggedIn = request("POST", "/api/auth/login", body = credentials(login, password))

        // then
        assertThat(loggedIn.statusCode()).isEqualTo(200)
        assertThat(loggedIn.headers().firstValue("Cache-Control")).hasValue("no-store")
        val response = dto(loggedIn)
        assertThat(response.keys).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn")
        assertThat(response["tokenType"]).isEqualTo("Bearer")
        assertThat((response["expiresIn"] as Number).toLong()).isEqualTo(900)
        val token = response["accessToken"] as String
        val signed = com.nimbusds.jwt.SignedJWT.parse(token)
        assertThat(signed.header.algorithm).isEqualTo(JWSAlgorithm.RS256)
        assertThat(signed.jwtClaimsSet.expirationTime.time - signed.jwtClaimsSet.issueTime.time)
            .isEqualTo(900_000)

        // when
        val me = request("GET", "/api/auth/me", token, spoofedUser = Long.MAX_VALUE.toString())

        // then
        assertThat(me.statusCode()).isEqualTo(200)
        assertThat(dto(me).keys).containsExactlyInAnyOrder("id", "login", "role")
        assertThat((dto(me)["id"] as Number).toLong()).isEqualTo(id)
        assertThat(dto(me)["login"]).isEqualTo(login)
        assertThat(dto(me)["role"]).isEqualTo("USER")
        assertThat(me.body()).doesNotContain(hash, password, "password", "status", "version")
        assertError(request("POST", "/api/auth/login", body = credentials(login.trim(), password)), 401)
        assertError(request("POST", "/api/auth/login", body = credentials(login, password.trim())), 401)
    }

    @Test
    fun `wrong unknown blocked and unsupported credentials return identical unauthorized responses`() {
        // given
        val password = "valid-password"
        val hash = passwords.encode(password).block(Duration.ofSeconds(10))!!
        user(login = "active", hash = hash)
        user(login = "blocked", hash = hash, status = "BLOCKED")
        user(login = "legacy", hash = "not-a-supported-hash")

        for ((login, supplied) in listOf("active" to "wrong-password", "unknown" to password,
            "blocked" to password, "legacy" to password)) {
            // when
            val response = request("POST", "/api/auth/login", body = credentials(login, supplied))

            // then
            assertError(response, 401)
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer")
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        }
    }

    @Test
    fun `invalid login payloads return sanitized bad request`() {
        // given
        val bodies = listOf("{}", "null", "{", credentials("", "password"),
            credentials(" ", "password"), credentials("a".repeat(101), "password"),
            credentials("login", ""), credentials("login", " "), credentials("login", "p".repeat(129)))

        for (body in bodies) {
            // when
            val response = request("POST", "/api/auth/login", body = body)

            // then
            assertError(response, 400)
        }
    }

    @Test
    fun `invalid signatures algorithms timestamps required claims and subjects are unauthorized`() {
        // given
        val id = user()
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().private as RSAPrivateKey
        val invalid = listOf(
            TestJwt.signClaims(TestJwt.claims(id.toString()), key = otherKey),
            TestJwt.signClaims(TestJwt.claims(id.toString()), algorithm = JWSAlgorithm.RS512),
            TestJwt.signClaims(TestJwt.claims(id.toString()).issuer("wrong")),
            TestJwt.signClaims(TestJwt.claims(id.toString()).issuer(null)),
            TestJwt.signClaims(TestJwt.claims(id.toString()).audience("wrong")),
            TestJwt.signClaims(TestJwt.claims(id.toString()).claim("aud", null)),
            TestJwt.signClaims(TestJwt.claims(id.toString()).subject(null)),
            TestJwt.signClaims(TestJwt.claims(id.toString()).issueTime(null)),
            TestJwt.signClaims(TestJwt.claims(id.toString()).issueTime(null)
                .expirationTime(Date.from(Instant.now().plusSeconds(20)))),
            TestJwt.signClaims(TestJwt.claims(id.toString()).expirationTime(null)),
            TestJwt.signClaims(TestJwt.claims(id.toString())
                .issueTime(Date.from(Instant.now().minusSeconds(1000)))
                .expirationTime(Date.from(Instant.now().minusSeconds(60)))),
            TestJwt.signClaims(TestJwt.claims(id.toString())
                .issueTime(Date.from(Instant.now().plusSeconds(120)))),
            TestJwt.signClaims(TestJwt.claims(id.toString())
                .expirationTime(Date.from(Instant.now().minusSeconds(1)))),
            "not-a-token",
        ) + listOf("0", "-1", "+1", "1.0", " 1", "1 ", "9223372036854775808", "text")
            .map { TestJwt.signClaims(TestJwt.claims(it)) }

        for (token in invalid) {
            // when
            val response = request("GET", "/api/auth/me", token)

            // then
            assertError(response, 401)
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer")
        }
        assertError(request("GET", "/api/auth/me", spoofedUser = id.toString()), 401)
    }

    @Test
    fun `same JWT uses current database role and status and deleted accounts cannot authenticate`() {
        // given
        val id = user()
        val token = TestJwt.signClaims(TestJwt.claims(id.toString()).claim("roles", listOf("ADMIN")))
        assertError(request("GET", "/api/admin/users", token), 403)
        assertThat(request("GET", "/api/stylists", token).statusCode()).isEqualTo(200)

        // when
        jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", id)

        // then
        assertThat(request("GET", "/api/admin/users", token).statusCode()).isEqualTo(200)
        assertThat(dto(request("GET", "/api/auth/me", token))["role"]).isEqualTo("ADMIN")
        assertError(request("GET", "/api/stylists", token), 403)

        // when
        jdbc.update("UPDATE app_user SET status = 'BLOCKED' WHERE id = ?", id)

        // then
        assertError(request("GET", "/api/admin/users", token), 403)
        assertError(request("GET", "/api/auth/me", token), 403)

        // when
        jdbc.update("DELETE FROM app_user WHERE id = ?", id)

        // then
        assertError(request("GET", "/api/auth/me", token), 401)
    }

    @Test
    fun `directory filters active stylists in database paginates by id and exposes identity only`() {
        // given
        val actor = user()
        val token = TestJwt.sign(actor)
        user(role = "ADMIN")
        user(role = "STYLIST", status = "BLOCKED")
        val stylists = (1..52).map { user(role = "STYLIST") }

        // when
        val first = request("GET", "/api/stylists", token)
        val next = request("GET", "/api/stylists?page=1&size=50", token)

        // then
        assertThat(first.statusCode()).isEqualTo(200)
        assertThat(next.statusCode()).isEqualTo(200)
        val firstRows: List<Map<String, Any?>> = JsonPath.read(first.body(), "$")
        val nextRows: List<Map<String, Any?>> = JsonPath.read(next.body(), "$")
        assertThat(firstRows.map { (it["id"] as Number).toLong() }).containsExactlyElementsOf(stylists.take(50))
        assertThat(nextRows.map { (it["id"] as Number).toLong() }).containsExactlyElementsOf(stylists.drop(50))
        (firstRows + nextRows).forEach { assertThat(it.keys).containsExactlyInAnyOrder("id", "login") }
        assertThat(first.headers().firstValue("X-Total-Count")).isEmpty()
        for (query in listOf("page=-1", "size=0", "size=51", "page=2147483647&size=50")) {
            assertError(request("GET", "/api/stylists?$query", token), 400)
        }
        assertError(request("GET", "/api/stylists", TestJwt.sign(stylists.first())), 403)
    }

    @Test
    fun `authentication repository failures are unavailable and do not disclose details`() {
        // given
        val id = user()
        doReturn(Mono.error<AppUser>(DataAccessResourceFailureException("private database detail")))
            .`when`(users).findById(anyLong())

        // when
        val response = request("GET", "/api/auth/me", TestJwt.sign(id))

        // then
        assertError(response, 503)
        assertThat(response.body()).doesNotContain("private", "database")
    }

    @Test
    fun `login is documented as public and unknown routes are denied`() {
        // given
        val token = TestJwt.sign(user())

        // when
        val docs = request("GET", "/v3/api-docs")
        val unknown = request("GET", "/not-a-public-route", token)

        // then
        assertThat(docs.statusCode()).isEqualTo(200)
        val login: Map<String, Any?> = JsonPath.read(docs.body(), "$.paths['/api/auth/login'].post")
        assertThat(login["security"]).isEqualTo(emptyList<Any>())
        for (path in listOf("login", "refresh", "logout")) {
            val operation: Map<String, Any?> = JsonPath.read(docs.body(), "$.paths['/api/auth/$path'].post")
            assertThat(operation["security"]).isEqualTo(emptyList<Any>())
            val headers: List<Map<String, Any?>> = JsonPath.read(docs.body(),
                "$.paths['/api/auth/$path'].post.parameters[?(@.name == 'X-Auth-Request')]")
            assertThat(headers).hasSize(1)
            assertThat(headers.single()["in"]).isEqualTo("header")
            assertThat(headers.single()["required"]).isEqualTo(true)
            assertThat((headers.single()["schema"] as Map<*, *>)["enum"]).isEqualTo(listOf("1"))
            val status = if (path == "logout") "204" else "200"
            val cookie: Map<String, Any?> = JsonPath.read(docs.body(),
                "$.paths['/api/auth/$path'].post.responses['$status'].headers['Set-Cookie']")
            assertThat(cookie["description"]).isNotNull()
        }
        for (path in listOf("refresh", "logout")) {
            val cookie: List<Map<String, Any?>> = JsonPath.read(docs.body(),
                "$.paths['/api/auth/$path'].post.parameters[?(@.name == 'ca_refresh')]")
            assertThat(cookie).hasSize(1)
            assertThat(cookie.single()["in"]).isEqualTo("cookie")
        }
        assertError(unknown, 403)
    }

    private fun user(
        login: String = UUID.randomUUID().toString(),
        hash: String = "private-hash",
        role: String = "USER",
        status: String = "ACTIVE",
    ): Long = jdbc.queryForObject(
        """INSERT INTO app_user (login, password_hash, role, status, version)
            VALUES (?, ?, ?::user_role, ?::user_status, 1) RETURNING id""",
        Long::class.java, login, hash, role, status,
    )!!

    private fun credentials(login: String, password: String) =
        """{"login":"$login","password":"$password"}"""

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        spoofedUser: String? = null,
    ): HttpResponse<String> = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        .timeout(Duration.ofSeconds(20)).apply {
            if (method == "POST" && path == "/api/auth/login") header("X-Auth-Request", "1")
            if (token != null) header("Authorization", "Bearer $token")
            if (spoofedUser != null) header("X-User-Id", spoofedUser)
            if (body != null) header("Content-Type", "application/json")
        }.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        .build(), HttpResponse.BodyHandlers.ofString())

    private fun dto(response: HttpResponse<String>): Map<String, Any?> = JsonPath.read(response.body(), "$")

    private fun assertError(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        val error = when (status) {
            400 -> "invalid_request"
            401 -> "unauthorized"
            403 -> "forbidden"
            else -> "service_unavailable"
        }
        assertThat(response.body()).isEqualTo("""{"error":"$error"}""")
    }
}
