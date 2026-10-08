package ru.itmo.clothesadvisor

import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.ZoneOffset
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClothesAdvisorApplicationTests : PostgresIntegrationTest() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var clock: Clock

    @Test
    fun `connects to PostgreSQL and exposes database health`() {
        assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
        assertThat(clock.instant().nano % 1_000).isZero()
        dataSource.connection.use { connection ->
            assertThat(connection.metaData.databaseProductName).isEqualTo("PostgreSQL")
        }
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

}
