package ru.itmo.clothesadvisor.controller.reference

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class ReferenceDataIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var users: AppUserService

    @Autowired private lateinit var jdbc: JdbcTemplate

    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun closeHttpClient() {
        client.close()
    }

    @Test
    fun `dictionaries return exact seeded DTOs in ID order`() {
        for ((path, table) in ENDPOINTS) {

            // given: a seeded dictionary endpoint

            // when
            val response = request(path)

            // then
            assertThat(rows(response)).isEqualTo(expectedSeeds(table))
            assertThat(response.headers().allValues("X-Total-Count")).isEmpty()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceEndpoints")
    fun `paging returns the requested part of a dictionary`(path: String, table: String) {

        // given
        val seeds = expectedSeeds(table)

        // when
        val firstPage = rows(request("$path?size=1"))

        // then
        assertThat(firstPage).containsExactly(seeds[0])

        // when
        val secondPage = rows(request("$path?page=1&size=1"))

        // then
        assertThat(secondPage).containsExactly(seeds[1])

        // when
        val lastPage = rows(request("$path?page=${seeds.size - 1}&size=1"))

        // then
        assertThat(lastPage).containsExactly(seeds.last())

        // when
        val emptyPage = rows(request("$path?page=${seeds.size}&size=1"))

        // then
        assertThat(emptyPage).isEmpty()

        // when
        val maximumOffsetPage = rows(request("$path?page=${Int.MAX_VALUE}&size=1"))

        // then
        assertThat(maximumOffsetPage).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings =
            [
                "page=-1",
                "size=0",
                "size=-1",
                "size=51",
                "page=abc",
                "size=abc",
                "page=1.5",
                "size=1.5",
                "page=2147483648",
                "size=2147483648",
                "page=42949673&size=50",
                "page=2147483647&size=2",
            ],
    )
    fun `invalid paging and overflowing offsets return bad request`(query: String) {

        // given: invalid paging from the parameterized case
        for (path in ENDPOINTS.keys) {

            // when
            val invalidPageStatus = request("$path?$query").statusCode()

            // then
            assertThat(invalidPageStatus).describedAs(path).isEqualTo(400)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceEndpoints")
    fun `pages stay bounded with more than fifty records`(path: String, table: String) {

        // given
        val prefix = "TEST${UUID.randomUUID().toString().replace("-", "")}"
        try {
            jdbc.update(
                "INSERT INTO $table (code, name) SELECT ? || n, 'Test ' || n FROM generate_series(1, 55) AS n",
                prefix,
            )
            val expected = databaseRows(table)

            // when
            val first = rows(request(path))
            val second = rows(request("$path?page=1&size=50"))

            // then
            assertThat(first).hasSize(50)
            assertThat(second).hasSize(expected.size - 50)
            assertThat(first + second).isEqualTo(expected)

            // when
            val emptyPage = rows(request("$path?page=2&size=50"))

            // then
            assertThat(emptyPage).isEmpty()
        } finally {
            jdbc.update("DELETE FROM $table WHERE code LIKE ?", "$prefix%")
        }
    }

    @Test
    fun `dictionaries are public regardless of actor header`() {

        // given
        val user = createUser()
        val actorId = actorId(user)
        for (path in ENDPOINTS.keys) {

            // when
            val activeActorStatus = request(path, actorId).statusCode()

            // then
            assertThat(activeActorStatus).isEqualTo(200)
        }

        // given: the same actor is now blocked
        users.changeRoleAndStatus(user.id!!, user.version, user.role, UserStatus.BLOCKED)
        for (path in ENDPOINTS.keys) {

            // when
            val response = request(path, actorId)

            // then
            assertThat(response.statusCode()).isEqualTo(200)
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["POST", "PUT", "PATCH", "DELETE"])
    internal fun `write methods are unavailable for a caller`(method: String) {

        // given: a write method unsupported by the dictionaries
        for (path in ENDPOINTS.keys) {

            // when
            val unsupportedMethodStatus = request(path, method = method).statusCode()

            // then
            assertThat(unsupportedMethodStatus).describedAs(path).isEqualTo(405)
        }
    }

    private fun createUser(role: UserRole = UserRole.USER): AppUser =
        jdbc.insertUser(UUID.randomUUID().toString(), "!", role)

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun request(
        path: String,
        actorId: String? = null,
        method: String = "GET",
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun rows(response: HttpResponse<String>): List<ReferenceRow> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read<List<Map<String, Any>>>(response.body(), "$").map {
            assertThat(it.keys).containsExactlyInAnyOrder("id", "code", "name")
            ReferenceRow(
                (it.getValue("id") as Number).toLong(),
                it.getValue("code") as String,
                it.getValue("name") as String,
            )
        }
    }

    private fun databaseRows(table: String): List<ReferenceRow> =
        jdbc.query("SELECT id, code, name FROM $table ORDER BY id") { row, _ ->
            ReferenceRow(row.getLong("id"), row.getString("code"), row.getString("name"))
        }

    private fun expectedSeeds(table: String): List<ReferenceRow> =
        SEEDS.getValue(table).mapIndexed { index, (code, name) ->
            ReferenceRow(index + 1L, code, name)
        }

    private data class ReferenceRow(val id: Long, val code: String, val name: String)

    companion object {
        private val ENDPOINTS =
            mapOf(
                "/api/wardrobe/categories" to "wardrobe_category",
                "/api/weather/precipitation-types" to "precipitation_type",
            )
        private val SEEDS =
            mapOf(
                "wardrobe_category" to
                    listOf(
                        "TOP" to "Верх",
                        "BOTTOM" to "Низ",
                        "ONE_PIECE" to "Платья и комбинезоны",
                        "OUTERWEAR" to "Верхняя одежда",
                        "FOOTWEAR" to "Обувь",
                        "ACCESSORIES" to "Аксессуары",
                    ),
                "precipitation_type" to
                    listOf(
                        "NONE" to "Нет",
                        "RAIN" to "Дождь",
                        "SNOW" to "Снег",
                        "SLEET" to "Дождь со снегом",
                    ),
            )

        @JvmStatic
        fun referenceEndpoints(): List<Arguments> = ENDPOINTS.map { (path, table) ->
            Arguments.of(path, table)
        }
    }
}
