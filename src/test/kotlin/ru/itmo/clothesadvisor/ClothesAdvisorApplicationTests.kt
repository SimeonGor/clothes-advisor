package ru.itmo.clothesadvisor

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClothesAdvisorApplicationTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `connects to PostgreSQL and exposes database health`() {
        dataSource.connection.use { connection ->
            assertThat(connection.metaData.databaseProductName).isEqualTo("PostgreSQL")
        }
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Int::class.java)).isEqualTo(1)

        val response = HttpClient.newHttpClient().use { client ->
            client.send(
                HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(JsonPath.read<String>(response.body(), "$.status")).isEqualTo("UP")
        assertThat(response.body()).doesNotContain(
            "\"components\"", "\"details\"", "jdbc:postgresql", postgres.password,
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
