package ru.itmo.clothesadvisor.fileservice

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.util.ReflectionTestUtils
import ru.itmo.clothesadvisor.fileservice.client.iam.IamTokenClient
import ru.itmo.clothesadvisor.fileservice.client.iam.IamTokenProvider
import ru.itmo.clothesadvisor.fileservice.config.IamProperties
import tools.jackson.databind.json.JsonMapper

class FileServiceApplicationTests {
    @TempDir lateinit var directory: Path

    @Test
    fun `startup and health leave IAM idle and shutdown terminates its HTTP client`() {

        // given
        val requests = AtomicInteger()
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val missingKey = directory.resolve("missing-key.json")
        try {

            // when
            val iamHttpClient = SpringApplicationBuilder(
                FileServiceApplication::class.java,
                IamObservationConfiguration::class.java,
            ).profiles("test").run(
                "--server.port=0",
                "--app.iam.service-account-key-file=$missingKey",
                "--app.iam.endpoint=http://127.0.0.1:${provider.address.port}/iam/v1/tokens",
            ).use { context ->
                val client = context.getBean(IamTokenClient::class.java)
                val mapper = context.getBean(JsonMapper::class.java)
                val port = context.environment.getRequiredProperty("local.server.port")
                val healthResponses = HttpClient.newHttpClient().use { http ->
                    listOf("health", "health/liveness", "health/readiness").map { path ->
                        http.send(
                            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/$path"))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    }
                }

                // then
                healthResponses.forEach { response ->
                    assertThat(response.statusCode()).isEqualTo(200)
                    val body = mapper.readTree(response.body())
                    assertThat(body.path("status").asString()).isEqualTo("UP")
                    assertThat(body.has("components")).isFalse()
                    assertThat(body.has("details")).isFalse()
                }
                assertThat(context.environment.getProperty("spring.application.name"))
                    .isEqualTo("file-service")
                assertThat(context.getBeansOfType(Clock::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(IamTokenProvider::class.java)).hasSize(1)
                assertThat(context.getBean(IamProperties::class.java).serviceAccountKeyFile)
                    .isEqualTo(missingKey.toString())
                verifyNoInteractions(client)
                assertThat(requests).hasValue(0)
                (ReflectionTestUtils.getField(client, "httpClient") as HttpClient).also {
                    assertThat(it.isTerminated).isFalse()
                }
            }

            assertThat(iamHttpClient.isTerminated).isTrue()
            assertThat(requests).hasValue(0)
        } finally {
            provider.stop(0)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class IamObservationConfiguration {
        companion object {
            @Bean
            @JvmStatic
            fun iamClientObserver(): BeanPostProcessor = object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                    if (bean is IamTokenClient) spy(bean) else bean
            }
        }
    }
}
