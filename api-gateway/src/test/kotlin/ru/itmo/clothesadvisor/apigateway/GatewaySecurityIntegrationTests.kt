package ru.itmo.clothesadvisor.apigateway

import com.nimbusds.jose.JWSAlgorithm
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewaySecurityIntegrationTests {
    @LocalServerPort private var port = 0

    @BeforeEach
    fun clearRequests() = received.clear()

    @Test
    fun `account routes and nested wardrobe routes retain ownership and forward only bearer identity`() {
        // given
        val authorization = "Bearer ${TestJwt.sign("7", Instant.now())}"
        val accountPaths = listOf("/api/auth/me", "/api/stylists", "/api/admin/users",
            "/api/admin/users/7", "/api/admin/users/7/password", "/api/me/stylist-access",
            "/api/me/stylist-access/3", "/api/stylist/clients")
        val wardrobePaths = listOf("/api/admin/users/7/wardrobe/items", "/api/admin/users/7/outfits",
            "/api/stylist/clients/7/wardrobe/items", "/api/wardrobe/items", "/api/wardrobe/categories")
        for ((service, paths) in mapOf("account" to accountPaths, "wardrobe" to wardrobePaths)) {
            paths.forEach { path ->
                // when
                val response = request(path, authorization, spoofedId = "99")

                // then
                assertThat(response.statusCode()).describedAs(path).isEqualTo(200)
                assertThat(response.body()).isEqualTo(service)
                assertThat(received.last()).isEqualTo(Received(service, path, authorization, null))
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty()
            }
        }
    }

    @Test
    fun `login pages docs and health are public while APIs and private paths remain protected`() {
        // given / when
        assertThat(request("/api/auth/login", method = "POST").body()).isEqualTo("account")
        for (path in listOf("/", "/index.html", "/app", "/app/assets/main.js")) {
            assertThat(request(path).body()).isEqualTo("frontend")
        }
        for ((path, service) in mapOf("/v3/api-docs/account" to "account", "/v3/api-docs/wardrobe" to "wardrobe")) {
            val response = request(path)
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body()).isEqualTo(service)
            assertThat(received.last().path).isEqualTo("/v3/api-docs")
        }
        assertThat(request("/actuator/health").statusCode()).isEqualTo(200)
        assertThat(request("/swagger-ui/index.html").statusCode()).isEqualTo(200)
        received.clear()

        // when / then
        for (path in listOf("/api/auth/login", "/api/auth/me", "/api/wardrobe/items", "/api/stylists")) {
            val response = request(path, spoofedId = "7")
            assertThat(response.statusCode()).describedAs(path).isEqualTo(401)
            assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}")
            assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).isEqualTo("Bearer")
        }
        val token = "Bearer ${TestJwt.sign("7", Instant.now())}"
        for (path in listOf("/internal/users/7", "/actuator/metrics", "/unknown")) {
            assertThat(request(path, token).statusCode()).describedAs(path).isEqualTo(403)
        }
        assertThat(received).isEmpty()
    }

    @Test
    fun `invalid signatures algorithms and required claims never reach upstream`() {
        // given
        val now = Instant.now()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val invalid = listOf(
            "malformed",
            TestJwt.signClaims(TestJwt.claims("7", now), other.private as RSAPrivateKey),
            TestJwt.signClaims(TestJwt.claims("7", now), algorithm = JWSAlgorithm.RS512),
            TestJwt.signClaims(TestJwt.claims("7", now).issuer("wrong")),
            TestJwt.signClaims(TestJwt.claims("7", now).audience("wrong")),
            TestJwt.signClaims(TestJwt.claims("7", now).issueTime(Date.from(now.minusSeconds(900)))
                .expirationTime(Date.from(now.minusSeconds(60)))),
            TestJwt.signClaims(TestJwt.claims("7", now).expirationTime(null)),
            TestJwt.signClaims(TestJwt.claims("7", now).issueTime(null)),
            TestJwt.signClaims(TestJwt.claims("7", now).issueTime(null).expirationTime(Date.from(now.plusSeconds(10)))),
            TestJwt.signClaims(TestJwt.claims("7", now).issueTime(Date.from(now.plusSeconds(60)))),
            TestJwt.signClaims(TestJwt.claims("7", now).notBeforeTime(Date.from(now.plusSeconds(60)))),
        ) + listOf("", "0", "-1", "x", "9223372036854775808").map { TestJwt.sign(it, now) }

        invalid.forEach { token ->
            // when
            val response = request("/api/wardrobe/items", "Bearer $token")

            // then
            assertThat(response.statusCode()).isEqualTo(401)
            assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}")
        }
        assertThat(received).isEmpty()
    }

    private fun request(path: String, authorization: String? = null, method: String = "GET", spoofedId: String? = null): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
            if (authorization != null) builder.header("Authorization", authorization)
            if (spoofedId != null) builder.header("X-User-Id", spoofedId)
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }

    private data class Received(val service: String, val path: String, val authorization: String?, val actor: String?)

    companion object {
        private val received = CopyOnWriteArrayList<Received>()
        private val account = upstream("account")
        private val wardrobe = upstream("wardrobe")
        private val frontend = upstream("frontend")

        private fun upstream(name: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    received += Received(name, it.requestURI.path, it.requestHeaders.getFirst("Authorization"),
                        it.requestHeaders.getFirst("X-User-Id"))
                    val body = name.toByteArray()
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.jwt.public-key") { TestJwt.publicKeyLocation }
            registry.add("spring.cloud.discovery.client.simple.instances.account-service[0].uri") {
                "http://127.0.0.1:${account.address.port}"
            }
            registry.add("spring.cloud.discovery.client.simple.instances.wardrobe-service[0].uri") {
                "http://127.0.0.1:${wardrobe.address.port}"
            }
            registry.add("FRONTEND_URL") { "http://127.0.0.1:${frontend.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun closeUpstreams() = listOf(account, wardrobe, frontend).forEach { it.stop(0) }
    }
}
