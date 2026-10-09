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
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClothesAdvisorApplicationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var dataSource: DataSource

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
