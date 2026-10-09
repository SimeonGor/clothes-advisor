package ru.itmo.clothesadvisor.controller.user

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrentUserIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `actor header is required only when current user is needed`() {
        listOf(null, "garbage", "0", "-1", "9223372036854775808").forEach { value ->

            // given: an invalid actor header

            // when
            val response = get("/api/wardrobe/items", value)

            // then
            assertThat(response.statusCode()).isEqualTo(400)
            assertThat(response.body()).isEqualTo("{\"error\":\"invalid_request\"}")
        }

        // when
        val missingActor = get("/api/wardrobe/items", Long.MAX_VALUE.toString())

        // then
        assertThat(missingActor.statusCode()).isEqualTo(404)
        listOf(
                "/api/admin/users",
                "/api/wardrobe/categories",
                "/api/weather/precipitation-types",
                "/actuator/health",
                "/",
            )
            .forEach {

                // given: an endpoint that does not require an actor

                // when
                val response = get(it)

                // then
                assertThat(response.statusCode()).describedAs(it).isEqualTo(200)
            }
    }

    @Test
    fun `all active roles can use their wardrobe but blocked actor is forbidden`() {
        UserRole.entries.forEach { role ->

            // given
            val user = jdbc.insertUser(role.name, "!", role)
            val id = user.id.toString()

            // when
            val activeResponse = get("/api/wardrobe/items", id)

            // then
            assertThat(activeResponse.statusCode()).isEqualTo(200)

            // given
            jdbc.update(
                "UPDATE app_user SET status = ?::user_status WHERE id = ?",
                UserStatus.BLOCKED.name,
                user.id,
            )

            // when
            val blockedResponse = get("/api/wardrobe/items", id)

            // then
            assertThat(blockedResponse.statusCode()).isEqualTo(403)

            // when
            val adminResponse = get("/api/admin/users/${user.id}")

            // then
            assertThat(adminResponse.statusCode()).isEqualTo(200)
        }
    }

    @Test
    fun `login and current account endpoints are absent`() {

        // given: the application uses actor headers rather than login endpoints

        // when
        val currentAccount = get("/api/auth/me")

        // then
        assertThat(currentAccount.statusCode()).isEqualTo(404)
        HttpClient.newHttpClient().use { client ->

            // given
            val request =
                HttpRequest.newBuilder(URI("http://localhost:$port/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build()

            // when
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            // then
            assertThat(response.statusCode()).isEqualTo(404)
        }
    }

    private fun get(path: String, actorId: String? = null): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            if (actorId != null) request.header("X-User-Id", actorId)
            client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
        }
}
