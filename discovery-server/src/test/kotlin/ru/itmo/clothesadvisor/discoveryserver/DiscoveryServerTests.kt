package ru.itmo.clothesadvisor.discoveryserver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DiscoveryServerTests {
    @LocalServerPort private var port = 0

    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    @AfterEach
    fun close() {
        http.close()
    }

    @Test
    fun `server uses central configuration and remains healthy without registering itself`() {

        // given
        val healthRequest =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/health"))
                .GET()
                .build()
        val registryRequest =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/eureka/apps"))
                .header("Accept", "application/json")
                .GET()
                .build()

        // when
        val healthResponse = http.send(healthRequest, HttpResponse.BodyHandlers.ofString())
        val registryResponse = http.send(registryRequest, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(healthResponse.statusCode()).isEqualTo(200)
        assertThat(mapper.readTree(healthResponse.body()).path("status").asString())
            .isEqualTo("UP")
        assertThat(registryResponse.statusCode()).isEqualTo(200)
        val applications = mapper.readTree(registryResponse.body()).path("applications")
        assertThat(applications).isNotNull
        assertThat(applications.path("application").isEmpty).isTrue()
        assertThat(registryResponse.body()).doesNotContain("DISCOVERY-SERVER")
    }
}
