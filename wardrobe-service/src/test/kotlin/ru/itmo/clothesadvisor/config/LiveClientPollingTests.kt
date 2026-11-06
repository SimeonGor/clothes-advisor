package ru.itmo.clothesadvisor.config

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cloud.config.client.ConfigClientProperties
import org.springframework.context.annotation.Import
import ru.itmo.clothesadvisor.client.account.AccountClient
import tools.jackson.databind.json.JsonMapper

class LiveClientPollingTests {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ExternalClientsConfiguration::class)
    class ClientApplication

    @ParameterizedTest
    @ValueSource(strings = ["local", "default"])
    fun `scheduled polling keeps imported URI and profile and retains valid settings across failures`(profile: String) {
        // given
        val remote = RemoteConfiguration()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange ->
                exchange.use {
                    remote.paths += it.requestURI.path
                    val response = remote.response
                    val body = JsonMapper().writeValueAsBytes(mapOf(
                        "name" to "wardrobe-service", "profiles" to listOf(profile),
                        "propertySources" to listOf(mapOf("name" to "remote", "source" to response.values)),
                    ))
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(response.status, body.size.toLong())
                    it.responseBody.write(body)
                    remote.polls.countDown()
                }
            }
            start()
        }
        val uri = "http://127.0.0.1:${server.address.port}"

        try {
            SpringApplicationBuilder(ClientApplication::class.java).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=$profile",
                "--spring.config.import=configserver:$uri",
                "--CONFIG_SERVER_URL=$uri",
                "--spring.cloud.config.uri=http://127.0.0.1:1",
                "--spring.cloud.config.enabled=true",
                "--eureka.client.enabled=false",
                "--app.client-config.poll-interval=100ms",
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            ).use { context ->
                val settings = context.getBean(LiveClientSettings::class.java)
                val client = context.getBean(AccountClient::class.java)
                val imported = context.getBean("configDataConfigClientProperties", ConfigClientProperties::class.java)
                val originalProfile = imported.profile

                // when
                remote.publish(Response(values = LiveClientSettingsTests.remoteValues() + mapOf(
                    "app.recommendation-client.read-timeout" to "3s", "app.account-client.read-timeout" to "4s",
                    "app.recommendation-client.write-timeout" to "2s",
                )))

                // then
                assertThat(settings.recommendationProperties().readTimeout.toMillis()).isEqualTo(3_000)
                assertThat(settings.recommendationProperties().writeTimeout.toMillis()).isEqualTo(2_000)
                assertThat(imported.uri).containsExactly(uri)
                assertThat(imported.profile).isEqualTo(originalProfile)
                assertThat(remote.paths.takeLast(2)).containsOnly("/wardrobe-service/$profile")
                assertThat(context.getBean(AccountClient::class.java)).isSameAs(client)
                assertThat(context.environment.getProperty("app.recommendation-client.read-timeout")).isEqualTo("90s")

                // when
                listOf(
                    Response(status = 503), Response(values = emptyMap()),
                    Response(values = LiveClientSettingsTests.remoteValues() + mapOf("app.account-client.read-timeout" to "bad")),
                    Response(values = LiveClientSettingsTests.remoteValues() + mapOf("app.recommendation-client.write-timeout" to "0s")),
                    Response(values = LiveClientSettingsTests.remoteValues() - "app.recommendation-client.write-timeout"),
                ).forEach { failure ->
                    remote.publish(failure)

                    // then
                    assertThat(settings.recommendationProperties().readTimeout.toMillis()).isEqualTo(3_000)
                    assertThat(settings.recommendationProperties().writeTimeout.toMillis()).isEqualTo(2_000)
                }

                // when
                remote.publish(Response(values = LiveClientSettingsTests.remoteValues() + mapOf(
                    "app.recommendation-client.read-timeout" to "7s",
                    "app.recommendation-client.write-timeout" to "6s",
                )))

                // then
                assertThat(settings.recommendationProperties().readTimeout.toMillis()).isEqualTo(7_000)
                assertThat(settings.recommendationProperties().writeTimeout.toMillis()).isEqualTo(6_000)
                assertThat(context.getBean(AccountClient::class.java)).isSameAs(client)
            }
        } finally {
            server.stop(0)
            executor.close()
        }
    }

    private data class Response(val status: Int = 200, val values: Map<String, Any> = LiveClientSettingsTests.remoteValues())

    private class RemoteConfiguration {
        @Volatile var response = Response()
        @Volatile var polls = CountDownLatch(0)
        val paths = CopyOnWriteArrayList<String>()

        fun publish(next: Response) {
            response = next
            // A later poll proves the preceding synchronous application has finished.
            polls = CountDownLatch(3)
            assertThat(polls.await(10, TimeUnit.SECONDS)).isTrue()
        }
    }
}
