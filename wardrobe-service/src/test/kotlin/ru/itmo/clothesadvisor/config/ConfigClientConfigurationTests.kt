package ru.itmo.clothesadvisor.config

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cloud.config.client.ConfigClientFailFastException
import org.springframework.context.ConfigurableApplicationContext

class ConfigClientConfigurationTests {
    @Test
    fun `required remote configuration imports properties and preserves environment secrets`() {

        // given
        var requestPath = ""
        var requestHeaders = ""
        val executor = Executors.newSingleThreadExecutor()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = executor
                createContext("/") { exchange ->
                    requestPath = exchange.requestURI.rawPath
                    requestHeaders = exchange.requestHeaders.entries.joinToString()
                    val body =
                        """
                            {"name":"wardrobe-service","profiles":["default"],"propertySources":[
                              {"name":"test","source":{"springdoc.api-docs.enabled":true}}
                            ]}
                        """.trimIndent().toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        var context: ConfigurableApplicationContext? = null

        try {

            // when
            context = application(server.address.port).run()

            // then
            assertThat(context.environment.getProperty("springdoc.api-docs.enabled"))
                .isEqualTo("true")
            assertThat(context.environment.getProperty("DB_PASSWORD"))
                .isEqualTo("synthetic-db-secret")
            assertThat(requestPath).isEqualTo("/wardrobe-service/default")
            assertThat(requestHeaders).doesNotContain("synthetic-db-secret")
        } finally {
            context?.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `required remote configuration fails startup when server is unavailable`() {

        // given
        val executor = Executors.newSingleThreadExecutor()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = executor
                createContext("/") { exchange ->
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                }
                start()
            }

        try {

            // when
            val failure = catchThrowable { application(server.address.port).run() }

            // then
            assertThat(failure).isNotNull
            assertThat(
                    generateSequence(failure) { it.cause }
                        .any { it is ConfigClientFailFastException },
                )
                .isTrue()
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun application(port: Int): SpringApplication =
        SpringApplication(ConfigClientTestConfiguration::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setDefaultProperties(
                mapOf(
                    "CONFIG_SERVER_URL" to "http://127.0.0.1:$port",
                    "DB_PASSWORD" to "synthetic-db-secret",
                ),
            )
        }
}

@TestConfiguration
class ConfigClientTestConfiguration
