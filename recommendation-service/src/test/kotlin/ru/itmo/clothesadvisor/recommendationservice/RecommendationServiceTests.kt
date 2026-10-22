package ru.itmo.clothesadvisor.recommendationservice

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.client.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationServiceTests {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var applicationContext: ApplicationContext

    @Autowired private lateinit var outfitClient: OpenAiOutfitClient

    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    @AfterEach
    fun close() {
        http.close()
    }

    @Test
    fun `service is healthy without credentials and does not contact provider at startup`() {

        // given
        val healthRequest =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/health"))
                .GET()
                .build()

        // when
        val healthResponse = http.send(healthRequest, HttpResponse.BodyHandlers.ofString())
        val probes = listOf("liveness", "readiness").map { group ->
            http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/health/$group"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        val metrics = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/metrics/http.server.requests"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val failure = catchThrowable { outfitClient.requireAvailable() }

        // then
        assertThat(healthResponse.statusCode()).isEqualTo(200)
        assertThat(mapper.readTree(healthResponse.body()).path("status").asString())
            .isEqualTo("UP")
        probes.forEach {
            assertThat(it.statusCode()).isEqualTo(200)
            assertThat(mapper.readTree(it.body()).path("status").asString()).isEqualTo("UP")
        }
        assertThat(metrics.statusCode()).isEqualTo(200)
        assertThat(mapper.readTree(metrics.body()).path("name").asString())
            .isEqualTo("http.server.requests")
        assertThat(applicationContext.environment.getProperty("spring.application.name"))
            .isEqualTo("recommendation-service")
        assertThat(applicationContext.getBeansOfType(Clock::class.java)).hasSize(1)
        assertThat(applicationContext.getBeansOfType(OpenAiOutfitClient::class.java)).hasSize(1)
        assertThat(failure).isInstanceOfSatisfying(AiOutfitException::class.java) {
            assertThat(it.failure).isEqualTo(AiOutfitFailure.UNAVAILABLE)
        }
        assertThat(providerRequests).hasValue(0)
    }

    companion object {
        private val providerRequests = AtomicInteger()
        private val provider =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/responses") { exchange ->
                    providerRequests.incrementAndGet()
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun providerEndpoint(registry: DynamicPropertyRegistry) {
            registry.add("app.openai.endpoint") {
                "http://127.0.0.1:${provider.address.port}/v1/responses"
            }
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() {
            provider.stop(0)
        }
    }
}
