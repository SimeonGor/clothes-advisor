package ru.itmo.clothesadvisor

import com.jayway.jsonpath.JsonPath
import io.micrometer.tracing.Tracer
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.registry.HealthContributorRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClothesAdvisorApplicationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var context: ApplicationContext
    @Autowired private lateinit var health: HealthContributorRegistry
    @Autowired private lateinit var tracer: Tracer

    @Test
    fun `normal application registers both external clients`() {
        // given
        val applicationContext = context

        // when
        val accounts = applicationContext.getBeansOfType(AccountClient::class.java)
        val recommendations = applicationContext.getBeansOfType(RecommendationClient::class.java)

        // then
        assertThat(accounts).hasSize(1)
        assertThat(recommendations).hasSize(1)
        assertThat(tracer).isNotSameAs(Tracer.NOOP)
        assertThat(context.getBeansOfType(SpanExporter::class.java)).isEmpty()
    }

    @Test
    fun `database failure changes readiness while liveness stays up and metrics remain available`() {
        // given
        HttpClient.newHttpClient().use { client ->
            fun get(path: String): HttpResponse<String> = client.send(
                HttpRequest.newBuilder(URI("http://localhost:$port/actuator/$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertThat(get("health/readiness").statusCode()).isEqualTo(200)
            assertThat(get("health/liveness").statusCode()).isEqualTo(200)
            val database = requireNotNull(health.unregisterContributor("db"))

            try {
                health.registerContributor("db", HealthIndicator {
                    Health.down().build()
                })

                // when
                val readiness = get("health/readiness")
                val liveness = get("health/liveness")

                // then
                assertThat(readiness.statusCode()).isEqualTo(503)
                assertThat(JsonPath.read<String>(readiness.body(), "$.status")).isEqualTo("DOWN")
                assertThat(liveness.statusCode()).isEqualTo(200)
                assertThat(JsonPath.read<String>(liveness.body(), "$.status")).isEqualTo("UP")
            } finally {
                health.unregisterContributor("db")
                health.registerContributor("db", database)
            }

            // when
            val restored = get("health/readiness")
            val metrics = get("metrics/http.server.requests")

            // then
            assertThat(restored.statusCode()).isEqualTo(200)
            assertThat(metrics.statusCode()).isEqualTo(200)
            assertThat(JsonPath.read<String>(metrics.body(), "$.name"))
                .isEqualTo("http.server.requests")
        }
    }

    @Test
    fun `connects to PostgreSQL and exposes database health`() {

        // given: the application is connected to the test PostgreSQL instance

        // when
        dataSource.connection.use { connection ->

            // then
            assertThat(connection.metaData.databaseProductName).isEqualTo("PostgreSQL")
        }

        // when
        val response =
            HttpClient.newHttpClient().use { client ->
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(JsonPath.read<String>(response.body(), "$.status")).isEqualTo("UP")
        assertThat(response.body())
            .doesNotContain(
                "\"components\"",
                "\"details\"",
                "jdbc:postgresql",
                postgres.password,
            )
    }
}
