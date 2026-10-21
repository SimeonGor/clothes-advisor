package ru.itmo.clothesadvisor.recommendationservice

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.itmo.clothesadvisor.client.ai.ChatGptCredentialFixture

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OutfitSelectionHttpTests.FixedClock::class)
class OutfitSelectionHttpTests {
    @TestConfiguration
    class FixedClock {
        @Bean @Primary
        fun providerClock(): Clock = credentials.clock
    }

    @LocalServerPort private var port = 0
    private val http = HttpClient.newHttpClient()
    private val valid = """
        {
          "weather":{
            "temperatureC":-1.23456789,"windSpeedMps":0,
            "precipitationCode":"NONE","precipitationName":"None"
          },
          "candidates":[{
            "itemId":7,"name":"Shirt","categoryCode":"TOP","categoryName":"Top",
            "color":"White","material":"Cotton","contentType":"image/png","imageBase64":"AQID"
          }]
        }
    """.trimIndent()

    @BeforeEach
    fun reset() {
        calls.set(0)
        status = 200
        providerBody = completed("{\"itemIds\":[7]}")
        blocked = null
        entered = CountDownLatch(1)
    }

    @AfterEach
    fun close() {
        blocked?.countDown()
        http.close()
    }

    @Test
    fun `real provider response is returned through HTTP`() {
        // given
        val request = request(valid)

        // when
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("""{"itemIds":[7]}""")
        assertThat(calls).hasValue(1)
    }

    @Test
    fun `malformed null and coerced input fails before provider`() {
        // given
        val malformed = listOf(
            "", "{", "null", "{}", valid.replace("\"itemId\":7", "\"itemId\":7.1"),
            valid.replace("\"itemId\":7", "\"itemId\":\"7\""),
            valid.replace("\"itemId\":7", "\"itemId\":null"),
            valid.replace("\"name\":\"Shirt\"", "\"name\":42"),
            valid.replace("\"name\":\"Shirt\"", "\"name\":null"),
            valid.replace("\"name\":\"Shirt\",", ""),
            valid.replace("\"windSpeedMps\":0", "\"windSpeedMps\":\"0\""),
            valid.replace("\"candidates\":[{", "\"candidates\":[null,{"),
            valid.replace("\"itemId\":7", "\"itemId\":7,\"itemId\":9"),
            "$valid {}",
        )

        malformed.forEach { body ->
            // when
            val response = http.send(request(body), HttpResponse.BodyHandlers.ofString())

            // then
            assertThat(response.statusCode()).describedAs(body.take(80)).isEqualTo(400)
            assertThat(response.body()).isEqualTo("""{"code":"invalid_request"}""")
        }
        assertThat(calls).hasValue(0)
    }

    @Test
    fun `refusal invalid completed response unfinished stream and unavailability map to codes`() {
        // given
        val cases = listOf(
            Triple(200, completed("{\"itemIds\":[]}"), 422 to "no_outfit"),
            Triple(200, "data: {\"type\":\"response.refusal.delta\",\"delta\":\"private\"}\n\n", 422 to "no_outfit"),
            Triple(200, completed("{\"itemIds\":[9]}"), 502 to "invalid_response"),
            Triple(200, "data: private malformed provider data\n\n", 502 to "invalid_response"),
            Triple(200, "data: [DONE]\n\n", 502 to "invalid_response"),
            Triple(503, "secret provider body", 503 to "unavailable"),
        )

        cases.forEach { (upstreamStatus, upstreamBody, expected) ->
            status = upstreamStatus
            providerBody = upstreamBody

            // when
            val response = http.send(request(valid), HttpResponse.BodyHandlers.ofString())

            // then
            assertThat(response.statusCode()).isEqualTo(expected.first)
            assertThat(response.body()).isEqualTo("""{"code":"${expected.second}"}""")
        }
        assertThat(calls).hasValue(cases.size)
    }

    @Test
    fun `blocked provider leaves HTTP responsive and times out with a sanitized error`() {
        // given
        val release = CountDownLatch(1)
        blocked = release

        // when
        val pending = http.sendAsync(request(valid), HttpResponse.BodyHandlers.ofString())
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
        val health = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/health"))
                .timeout(Duration.ofMillis(800)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val response = pending.get(5, TimeUnit.SECONDS)
        release.countDown()

        // then
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(response.statusCode()).isEqualTo(504)
        assertThat(response.body()).isEqualTo("""{"code":"timeout"}""")
        assertThat(calls).hasValue(1)
    }

    private fun request(body: String) =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/outfit-selections"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()

    companion object {
        private val directory = Files.createTempDirectory("recommendation-http-credentials-")
        private val credentials = ChatGptCredentialFixture(directory)
        private val calls = AtomicInteger()
        @Volatile private var status = 200
        @Volatile private var providerBody = ""
        @Volatile private var blocked: CountDownLatch? = null
        @Volatile private var entered = CountDownLatch(1)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/responses") { exchange ->
                calls.incrementAndGet()
                exchange.requestBody.readAllBytes()
                blocked?.let { entered.countDown(); it.await(5, TimeUnit.SECONDS) }
                val bytes = providerBody.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        private fun completed(text: String) = """
            data: {"type":"response.completed","response":{"status":"completed","output":[{
              "type":"message","role":"assistant","status":"completed",
              "content":[{"type":"output_text","text":${credentials.mapper.writeValueAsString(text)}}]
            }]}}
        """.trimIndent().replace("\n", "") + "\n\n"

        @JvmStatic
        @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.openai.endpoint") { "http://127.0.0.1:${provider.address.port}/responses" }
            registry.add("app.openai.credentials-directory") { credentials.directory.toString() }
            registry.add("app.openai.model") { "test-model" }
            registry.add("app.openai.timeout") { "1s" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            blocked?.countDown()
            provider.stop(0)
            executor.close()
            Files.deleteIfExists(directory.resolve("credentials.json"))
            Files.deleteIfExists(directory.resolve("registration.json"))
            Files.deleteIfExists(directory)
        }
    }
}
