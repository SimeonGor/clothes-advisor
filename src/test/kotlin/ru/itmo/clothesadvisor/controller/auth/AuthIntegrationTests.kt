package ru.itmo.clothesadvisor.controller.auth

import com.jayway.jsonpath.JsonPath
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.config.SecurityConfiguration
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.dto.auth.LoginRequest
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class, AuthTestEndpoints::class)
class AuthIntegrationTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var users: AppUserService

    @Autowired
    private lateinit var passwords: PasswordEncoder

    @Autowired
    private lateinit var key: SecretKey

    @Test
    fun `login and me expose only public fields for every role and issue a thirty minute token`() {
        UserRole.entries.forEach { role ->
            val user = createUser(role)
            val response = login(user.login)
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(JsonPath.read<Map<String, Any>>(response.body(), "$").keys)
                .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn")
            assertThat(JsonPath.read<String>(response.body(), "$.tokenType")).isEqualTo("Bearer")
            assertThat(JsonPath.read<Int>(response.body(), "$.expiresIn")).isEqualTo(1800)
            val token = accessToken(response)
            val claims = SignedJWT.parse(token).jwtClaimsSet
            val issuedAt = TestTimeConfiguration.FIXED_TIME.truncatedTo(ChronoUnit.SECONDS)
            assertThat(claims.issueTime.toInstant()).isEqualTo(issuedAt)
            assertThat(claims.notBeforeTime.toInstant()).isEqualTo(issuedAt)
            assertThat(claims.expirationTime.toInstant()).isEqualTo(issuedAt.plusSeconds(1800))
            assertThat(claims.subject).isEqualTo(user.id.toString())
            assertThat(claims.issuer).isEqualTo(SecurityConfiguration.ISSUER)
            assertThat(claims.audience).containsExactly(SecurityConfiguration.AUDIENCE)
            assertThat(claims.claims).doesNotContainKeys("role", "scope", "password", "passwordHash")
            val me = get("/api/auth/me", token)
            assertThat(me.statusCode()).isEqualTo(200)
            assertThat(JsonPath.read<Map<String, Any>>(me.body(), "$").keys)
                .containsExactlyInAnyOrder("id", "login", "role")
            assertThat(JsonPath.read<Number>(me.body(), "$.id").toLong()).isEqualTo(user.id)
            assertThat(JsonPath.read<String>(me.body(), "$.login")).isEqualTo(user.login)
            assertThat(JsonPath.read<String>(me.body(), "$.role")).isEqualTo(role.name)
            listOf(response, me).forEach {
                assertThat(it.body()).doesNotContain(PASSWORD, user.passwordHash)
                assertThat(it.headers().allValues("Set-Cookie")).isEmpty()
            }
        }
    }

    @Test
    fun `wrong unknown and blocked credentials return the same generic error`() {
        val user = createUser()
        val blocked = createUser(status = UserStatus.BLOCKED)
        listOf(login(user.login, "wrong"), login("unknown"), login(blocked.login)).forEach {
            assertError(it, 401, "unauthorized")
        }
        assertThat(LoginRequest("alice", PASSWORD).toString()).doesNotContain(PASSWORD)
    }

    @Test
    fun `invalid JSON and blank or missing fields produce generic bad request`() {
        listOf("{", "{}", "{\"login\":\"alice\"}", "{\"login\":\" \",\"password\":\"x\"}",
            "{\"login\":\"alice\",\"password\":\" \"}", "{\"login\":null,\"password\":\"x\"}",
        ).forEach { assertError(post(it), 400, "invalid_request") }
    }

    @Test
    fun `bcrypt limit uses UTF-8 bytes and accepts exactly seventy two`() {
        listOf("a".repeat(72), "я".repeat(36)).forEach { password ->
            val user = createUser(password = password)
            assertThat(login(user.login, password).statusCode()).isEqualTo(200)
            assertError(login(user.login, password + "a"), 400, "invalid_request")
        }
    }

    @Test
    fun `missing malformed and unsigned bearer tokens are unauthorized and health is public`() {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200)
        assertThat(get("/actuator/health/missing").statusCode()).isEqualTo(404)
        assertError(get("/api/auth/me"), 401, "unauthorized")
        assertError(get("/unmapped"), 401, "unauthorized")
        val user = createUser()
        listOf("not.a.jwt", PlainJWT(claims(user.id.toString())).serialize()).forEach {
            assertError(get("/api/auth/me", it), 401, "unauthorized")
        }
        val token = accessToken(login(user.login))
        assertError(get("/api/auth/me?access_token=$token"), 401, "unauthorized")
        assertThat(get("/unmapped", token).statusCode()).isEqualTo(404)
    }

    @Test
    fun `signed tokens reject wrong signature algorithm issuer audience subject expiry and future nbf`() {
        val id = createUser().id.toString()
        val base = claims(id)
        val invalid = listOf(
            signed(base, ByteArray(32) { 7 }),
            signed(base, ByteArray(64) { 8 }, JWSAlgorithm.HS512),
            signed(JWTClaimsSet.Builder(base).issuer("other").build()),
            signed(JWTClaimsSet.Builder(base).audience("other").build()),
            signed(JWTClaimsSet.Builder(base).audience(listOf(SecurityConfiguration.AUDIENCE, "extra")).build()),
            signed(JWTClaimsSet.Builder(base).subject("not-a-number").build()),
            signed(JWTClaimsSet.Builder(base).subject("999999999999999999999999").build()),
            signed(JWTClaimsSet.Builder(base).subject(Long.MAX_VALUE.toString()).build()),
            signed(JWTClaimsSet.Builder(base).subject(null).build()),
            signed(JWTClaimsSet.Builder(base).expirationTime(null).build()),
            signed(JWTClaimsSet.Builder(base).notBeforeTime(Date.from(TestTimeConfiguration.FIXED_TIME.plusSeconds(1))).build()),
        )
        invalid.forEach { assertError(get("/api/auth/me", it), 401, "unauthorized") }
    }

    @Test
    fun `expiry is rejected at and after its boundary and accepted before it`() {
        val id = createUser().id.toString()
        val second = TestTimeConfiguration.FIXED_TIME.truncatedTo(ChronoUnit.SECONDS)
        listOf(-1L to 401, 0L to 401, 1L to 200).forEach { (offset, status) ->
            val token = signed(claims(id, second.plusSeconds(offset)))
            val response = get("/api/auth/me", token)
            assertThat(response.statusCode()).isEqualTo(status)
            if (status == 401) assertError(response, 401, "unauthorized")
        }
        val decoder = SecurityConfiguration().jwtDecoder(key, Clock.fixed(second, ZoneOffset.UTC))
        assertThatThrownBy { decoder.decode(signed(claims(id, second))) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `same token immediately reflects role changes and account blocking`() {
        val user = createUser(UserRole.USER)
        val token = accessToken(login(user.login))
        assertError(get("/test/admin", token), 403, "forbidden")
        val forgedRole = signed(JWTClaimsSet.Builder(claims(user.id.toString()))
            .claim("role", "ADMIN").claim("scope", "ADMIN").build())
        assertError(get("/test/admin", forgedRole), 403, "forbidden")
        val admin = users.changeRoleAndStatus(user.id!!, user.version, UserRole.ADMIN, UserStatus.ACTIVE)
        assertThat(get("/test/admin", token).statusCode()).isEqualTo(200)
        assertThat(JsonPath.read<String>(get("/api/auth/me", token).body(), "$.role")).isEqualTo("ADMIN")
        val stylist = users.changeRoleAndStatus(admin.id!!, admin.version, UserRole.STYLIST, UserStatus.ACTIVE)
        assertError(get("/test/admin", token), 403, "forbidden")
        assertThat(JsonPath.read<String>(get("/api/auth/me", token).body(), "$.role")).isEqualTo("STYLIST")
        users.changeRoleAndStatus(stylist.id!!, stylist.version, UserRole.STYLIST, UserStatus.BLOCKED)
        assertError(get("/api/auth/me", token), 401, "unauthorized")
    }

    @Test
    fun `default profile does not create local accounts`() {
        listOf("user", "stylist", "admin").forEach { assertThat(users.findByLogin(it)).isNull() }
    }

    private fun createUser(
        role: UserRole = UserRole.USER,
        status: UserStatus = UserStatus.ACTIVE,
        password: String = PASSWORD,
    ): AppUser = users.create(UUID.randomUUID().toString(), requireNotNull(passwords.encode(password)), role, status)

    private fun login(login: String, password: String = PASSWORD): HttpResponse<String> =
        post("{\"login\":\"$login\",\"password\":\"$password\"}")

    private fun post(body: String): HttpResponse<String> = send(
        HttpRequest.newBuilder(URI("http://localhost:$port/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
    )

    private fun get(path: String, token: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET()
        if (token != null) request.header("Authorization", "Bearer $token")
        return send(request.build())
    }

    private fun send(request: HttpRequest): HttpResponse<String> = HttpClient.newHttpClient().use {
        it.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun accessToken(response: HttpResponse<String>): String = JsonPath.read(response.body(), "$.accessToken")

    private fun assertError(response: HttpResponse<String>, status: Int, error: String) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEqualTo("{\"error\":\"$error\"}")
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty()
    }

    private fun claims(id: String, expiration: Instant = TestTimeConfiguration.FIXED_TIME.plusSeconds(1800)): JWTClaimsSet =
        JWTClaimsSet.Builder().subject(id).issuer(SecurityConfiguration.ISSUER)
            .audience(SecurityConfiguration.AUDIENCE)
            .issueTime(Date.from(TestTimeConfiguration.FIXED_TIME.minusSeconds(60)))
            .notBeforeTime(Date.from(TestTimeConfiguration.FIXED_TIME.minusSeconds(60)))
            .expirationTime(Date.from(expiration)).build()

    private fun signed(claims: JWTClaimsSet, secret: ByteArray = key.encoded, algorithm: JWSAlgorithm = JWSAlgorithm.HS256): String =
        SignedJWT(JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims).apply {
            sign(MACSigner(secret))
        }.serialize()

    companion object {
        private const val PASSWORD = "test-password"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@TestConfiguration
internal class AuthTestEndpoints {
    @Bean
    fun adminTestController(): AdminTestController = AdminTestController()
}

@RestController
internal class AdminTestController {
    @GetMapping("/test/admin")
    @PreAuthorize("hasRole('ADMIN')")
    fun admin(): Map<String, String> = mapOf("result" to "ok")
}
