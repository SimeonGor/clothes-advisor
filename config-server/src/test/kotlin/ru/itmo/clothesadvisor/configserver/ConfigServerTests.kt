package ru.itmo.clothesadvisor.configserver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            "DB_PASSWORD=synthetic-db-secret",
            "YANDEX_SERVICE_ACCOUNT_KEY=synthetic-iam-secret",
            "OPENAI_API_KEY=synthetic-openai-secret",
        ],
)
class ConfigServerTests {
    @LocalServerPort private var port = 0

    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    @AfterEach
    fun close() {
        http.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "local"])
    fun `native repository serves profile configuration without secrets`(profile: String) {

        // given
        val request =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/wardrobe-service/$profile"))
                .GET()
                .build()

        // when
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        val document = mapper.readTree(response.body())
        val sources = document.path("propertySources")
        assertThat(sources).isNotEmpty
        val enabled = property(sources, "springdoc.api-docs.enabled")
        val paths = property(sources, "springdoc.paths-to-match")
        assertThat(enabled?.asBoolean())
            .isEqualTo(profile == "local")
        assertThat(paths?.asString()).isEqualTo("/api/**")
        assertThat(response.body())
            .doesNotContain(
                "synthetic-db-secret",
                "synthetic-iam-secret",
                "synthetic-openai-secret",
                "DB_PASSWORD",
                "YANDEX_SERVICE_ACCOUNT_KEY",
                "OPENAI_API_KEY",
            )
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "local"])
    fun `native repository serves account discovery and health configuration without secrets`(
        profile: String,
    ) {
        // given
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/account-service/$profile"))
            .GET().build()

        // when
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        val sources = mapper.readTree(response.body()).path("propertySources")
        assertThat(sources).isNotEmpty
        assertThat(property(sources, "server.port")).isNotNull
        assertThat(property(sources, "eureka.client.service-url.defaultZone")?.asString())
            .contains("localhost:8761/eureka/")
        assertThat(property(sources, "eureka.client.healthcheck.enabled")?.asBoolean()).isTrue()
        assertThat(property(sources, "eureka.instance.prefer-ip-address")?.asBoolean()).isTrue()
        assertThat(property(sources, "management.endpoints.web.exposure.include")?.asString())
            .isEqualTo("health")
        assertThat(property(sources, "management.endpoint.health.show-details")?.asString())
            .isEqualTo("never")
        assertThat(property(sources, "management.health.config.enabled")?.asBoolean()).isFalse()
        assertThat(response.body()).doesNotContain(
            "synthetic-db-secret", "DB_PASSWORD", "spring.r2dbc", "spring.flyway",
            "YANDEX_SERVICE_ACCOUNT_KEY", "OPENAI_API_KEY",
        )
    }

    private fun property(sources: JsonNode, name: String): JsonNode? =
        sources.firstNotNullOfOrNull { source ->
            source.path("source").path(name).takeUnless { it.isMissingNode }
        }
}
