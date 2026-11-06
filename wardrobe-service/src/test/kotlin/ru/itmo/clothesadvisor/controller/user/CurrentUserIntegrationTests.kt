package ru.itmo.clothesadvisor.controller.user

import com.nimbusds.jose.JWSAlgorithm
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.TestJwt
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrentUserIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `all APIs require JWT including catalogs and legacy headers cannot authenticate`() {
        // given
        val user = jdbc.insertUser("user", "!", UserRole.USER)
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext::class.java)
        for (path in listOf("/api/wardrobe/items", "/api/wardrobe/categories", "/api/weather/precipitation-types")) {
            // when
            val anonymous = get(path, spoofedId = user.id.toString())
            val authenticated = get(path, bearer(user.id.toString()))

            // then
            assertThat(anonymous.statusCode()).describedAs(path).isEqualTo(401)
            assertThat(anonymous.body()).isEqualTo("{\"error\":\"unauthorized\"}")
            assertThat(authenticated.statusCode()).describedAs(path).isEqualTo(200)
            assertThat(authenticated.headers().allValues("Set-Cookie")).isEmpty()
        }
        // when / then
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200)
        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(200)
        assertThat(get("/unknown", bearer(user.id.toString())).statusCode()).isEqualTo(403)
    }

    @Test
    fun `invalid JWTs are rejected before contacting accounts`() {
        // given
        val now = clock.instant()
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val invalid = listOf(
            "not-a-jwt",
            TestJwt.signClaims(TestJwt.claims("1", now), otherKey.private as RSAPrivateKey),
            TestJwt.signClaims(TestJwt.claims("1", now), algorithm = JWSAlgorithm.RS512),
            TestJwt.signClaims(TestJwt.claims("1", now).issuer("wrong")),
            TestJwt.signClaims(TestJwt.claims("1", now).audience("wrong")),
            TestJwt.signClaims(TestJwt.claims("1", now).expirationTime(Date.from(now.minusSeconds(60)))
                .issueTime(Date.from(now.minusSeconds(900)))),
            TestJwt.signClaims(TestJwt.claims("1", now).expirationTime(null)),
            TestJwt.signClaims(TestJwt.claims("1", now).issueTime(null)),
            TestJwt.signClaims(TestJwt.claims("1", now).issueTime(null).expirationTime(Date.from(now.plusSeconds(10)))),
            TestJwt.signClaims(TestJwt.claims("1", now).issueTime(Date.from(now.plusSeconds(60)))),
            TestJwt.signClaims(TestJwt.claims("1", now).notBeforeTime(Date.from(now.plusSeconds(60)))),
        ) + listOf("", "0", "-1", "x", "9223372036854775808").map { TestJwt.sign(it, now) }
        clearInvocations(reactiveAccounts)

        invalid.forEach { token ->
            // when
            val response = get("/api/wardrobe/items", "Bearer $token")

            // then
            assertThat(response.statusCode()).isEqualTo(401)
            assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}")
        }
        org.mockito.Mockito.verifyNoInteractions(reactiveAccounts)
    }

    @Test
    fun `all active roles retain their wardrobe and blocked or deleted actors are rejected`() {
        UserRole.entries.forEach { role ->
            // given
            val user = jdbc.insertUser(role.name, "!", role)
            val authorization = bearer(user.id.toString())

            // when / then
            assertThat(get("/api/wardrobe/items", authorization).statusCode()).isEqualTo(200)

            // given
            jdbc.update("UPDATE app_user SET status = ?::user_status WHERE id = ?", UserStatus.BLOCKED.name, user.id)

            // when / then
            val blocked = get("/api/wardrobe/items", authorization)
            assertThat(blocked.statusCode()).isEqualTo(403)
            assertThat(blocked.body()).isEqualTo("{\"error\":\"forbidden\"}")
        }
        // when / then
        assertThat(get("/api/wardrobe/items", bearer(Long.MAX_VALUE.toString())).statusCode()).isEqualTo(401)
    }

    @Test
    fun `verified subject wins over spoofed header and resolver reuses account identity`() {
        // given
        val owner = jdbc.insertUser("owner", "!", UserRole.USER)
        val blocked = jdbc.insertUser("blocked", "!", UserRole.USER, UserStatus.BLOCKED)
        clearInvocations(reactiveAccounts)

        // when
        val response = get("/api/wardrobe/items", bearer(owner.id.toString()), blocked.id.toString())

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        verify(reactiveAccounts, times(1)).getUser(owner.id)
        verify(reactiveAccounts, times(0)).getUser(blocked.id)
    }

    @Test
    fun `account errors and empty responses return sanitized unavailable responses`() {
        // given
        val user = jdbc.insertUser("user", "!", UserRole.USER)
        for (result in listOf(Mono.error(AccountUnavailableException()), Mono.empty<ru.itmo.clothesadvisor.dto.account.AccountUserResponse>())) {
            doReturn(result).`when`(reactiveAccounts).getUser(user.id)

            // when
            val response = get("/api/wardrobe/categories", bearer(user.id.toString()))

            // then
            assertThat(response.statusCode()).isEqualTo(503)
            assertThat(response.body()).isEqualTo("{\"error\":\"service_unavailable\"}")
        }
    }

    @Test
    fun `login and current account endpoints belong to the account service`() {
        // given
        val user = jdbc.insertUser("user", "!", UserRole.USER)

        // when / then
        assertThat(get("/api/auth/me", bearer(user.id.toString())).statusCode()).isEqualTo(404)
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/auth/login"))
                .header("Authorization", bearer(user.id.toString()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404)
        }
    }

    private fun get(path: String, authorization: String? = null, spoofedId: String? = null): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            if (authorization != null) request.header("Authorization", authorization)
            if (spoofedId != null) request.header("X-User-Id", spoofedId)
            client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
        }
}
