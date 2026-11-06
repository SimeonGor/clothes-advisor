package ru.itmo.clothesadvisor.accountservice.config

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
    fun `account imports remote configuration and keeps database values local`() {
        // given
        var requestPath = ""
        var requestHeaders = ""
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange ->
                requestPath = exchange.requestURI.rawPath
                requestHeaders = exchange.requestHeaders.entries.joinToString()
                val body = """
                    {"name":"account-service","profiles":["default"],"propertySources":[
                      {"name":"test","source":{"server.port":8187}}
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
            val environment = context.environment
            assertThat(environment.getProperty("server.port")).isEqualTo("8187")
            assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("account-service")
            assertThat(environment.getProperty("spring.r2dbc.url"))
                .isEqualTo("r2dbc:postgresql://127.0.0.1:5432/synthetic_account")
            assertThat(environment.getProperty("spring.flyway.url"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:5432/synthetic_account")
            assertThat(environment.getProperty("spring.r2dbc.username"))
                .isEqualTo("synthetic-account")
            assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("synthetic-account")
            assertThat(environment.getProperty("spring.r2dbc.password"))
                .isEqualTo("synthetic-secret")
            assertThat(environment.getProperty("spring.flyway.password"))
                .isEqualTo("synthetic-secret")
            assertThat(environment.getProperty("spring.cloud.config.fail-fast")).isEqualTo("true")
            assertThat(environment.getProperty("spring.cloud.config.request-connect-timeout"))
                .isEqualTo("2000")
            assertThat(environment.getProperty("spring.cloud.config.request-read-timeout"))
                .isEqualTo("5000")
            assertThat(requestPath).isEqualTo("/account-service/default")
            assertThat(requestHeaders).doesNotContain("synthetic-secret")
        } finally {
            context?.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `required account configuration fails startup when config server is unavailable`() {
        // given
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
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
            assertThat(generateSequence(failure) { it.cause }
                .any { it is ConfigClientFailFastException }).isTrue()
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `test profile imports account native configuration with cloud clients disabled`() {
        // given
        val app = application(1).apply { setAdditionalProfiles("test") }

        // when
        app.run().use { context ->
            // then
            val environment = context.environment
            assertThat(environment.getProperty("spring.cloud.config.enabled")).isEqualTo("false")
            assertThat(environment.getProperty("eureka.client.enabled")).isEqualTo("false")
            assertThat(environment.getProperty("eureka.client.healthcheck.enabled"))
                .isEqualTo("true")
            assertThat(environment.getProperty("eureka.instance.prefer-ip-address"))
                .isEqualTo("true")
            assertThat(environment.getProperty("eureka.instance.health-check-url-path"))
                .isEqualTo("/actuator/health")
            assertThat(environment.getProperty("eureka.instance.status-page-url-path"))
                .isEqualTo("/actuator/health")
            assertThat(environment.getProperty("eureka.client.timeout.connect-timeout"))
                .isEqualTo("2000")
            assertThat(environment.getProperty("eureka.client.timeout.connect-request-timeout"))
                .isEqualTo("2000")
            assertThat(environment.getProperty("eureka.client.timeout.socket-timeout"))
                .isEqualTo("5000")
            assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,metrics")
            assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never")
            assertThat(environment.getProperty("management.health.config.enabled"))
                .isEqualTo("false")
        }
    }

    private fun application(port: Int): SpringApplication =
        SpringApplication(AccountConfigClientTestConfiguration::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setDefaultProperties(mapOf(
                "CONFIG_SERVER_URL" to "http://127.0.0.1:$port",
                "DB_R2DBC_URL" to "r2dbc:postgresql://127.0.0.1:5432/synthetic_account",
                "DB_URL" to "jdbc:postgresql://127.0.0.1:5432/synthetic_account",
                "DB_USER" to "synthetic-account",
                "DB_PASSWORD" to "synthetic-secret",
            ))
        }
}

@TestConfiguration
class AccountConfigClientTestConfiguration
