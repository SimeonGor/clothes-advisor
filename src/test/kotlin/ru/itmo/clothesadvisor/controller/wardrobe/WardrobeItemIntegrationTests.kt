package ru.itmo.clothesadvisor.controller.wardrobe

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class WardrobeItemIntegrationTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var users: AppUserService

    @Autowired
    private lateinit var passwords: PasswordEncoder

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun closeHttpClient() {
        client.close()
    }

    @Test
    fun `CRUD returns exact DTOs and archives previous and final values`() {
        val owner = createUser()
        val token = login(owner)
        val created = request("POST", token = token, body = body())
        assertThat(created.statusCode()).isEqualTo(201)
        val original = dto(created)
        val id = number(original, "id")
        val path = "/api/wardrobe/items/$id"
        assertThat(created.headers().firstValue("Location")).hasValue(path)
        assertThat(number(original, "version")).isEqualTo(1)
        assertThat(original["createdAt"]).isEqualTo(TestTimeConfiguration.FIXED_TIME.toString())
        assertThat(original["modifiedAt"]).isEqualTo(original["createdAt"])
        assertThat(request("GET", path, token).statusCode()).isEqualTo(200)
        assertThat(dto(request("GET", path, token))).isEqualTo(original)
        assertThat(rows(request("GET", token = token))).containsExactly(original)
        assertThat(history(id)).isEmpty()

        val earlier = TestTimeConfiguration.FIXED_TIME.minusSeconds(10)
        jdbc.update("UPDATE wardrobe_item SET created_at = ?, modified_at = ? WHERE id = ?",
            Timestamp.from(earlier), Timestamp.from(earlier), id)
        val changedResponse = request("PUT", path, token, body(1, "Jacket", 2, "Blue", "Wool"))
        assertThat(changedResponse.statusCode()).isEqualTo(200)
        val changed = dto(changedResponse)
        assertThat(number(changed, "version")).isEqualTo(2)
        assertThat(changed["createdAt"]).isEqualTo(earlier.toString())
        assertThat(changed["modifiedAt"]).isEqualTo(TestTimeConfiguration.FIXED_TIME.toString())
        assertThat(changed["name"]).isEqualTo("Jacket")
        assertThat(number(changed, "categoryId")).isEqualTo(2)
        assertThat(changed["color"]).isEqualTo("Blue")
        assertThat(changed["material"]).isEqualTo("Wool")
        assertThat(jdbc.queryForObject("SELECT owner_id FROM wardrobe_item WHERE id = ?", Long::class.java, id))
            .isEqualTo(owner.id)
        assertThat(history(id)).containsExactly(listOf(1L, "Shirt", 1L, "White", "Cotton", earlier,
            TestTimeConfiguration.FIXED_TIME))

        val deleted = request("DELETE", "$path?version=2", token)
        assertThat(deleted.statusCode()).isEqualTo(204)
        assertThat(deleted.body()).isEmpty()
        assertThat(request("GET", path, token).statusCode()).isEqualTo(404)
        assertThat(rows(request("GET", token = token))).isEmpty()
        assertThat(history(id)).containsExactly(
            listOf(1L, "Shirt", 1L, "White", "Cotton", earlier, TestTimeConfiguration.FIXED_TIME),
            listOf(2L, "Jacket", 2L, "Blue", "Wool", TestTimeConfiguration.FIXED_TIME, TestTimeConfiguration.FIXED_TIME),
        )
    }

    @Test
    fun `unchanged PUT stays at the same version and stale requests cannot mutate it`() {
        val token = login(createUser())
        val original = dto(request("POST", token = token, body = body()))
        val id = number(original, "id")
        val path = "/api/wardrobe/items/$id"
        val unchanged = request("PUT", path, token, body(1))
        assertThat(unchanged.statusCode()).isEqualTo(200)
        assertThat(dto(unchanged)).isEqualTo(original)
        assertThat(history(id)).isEmpty()
        assertThat(request("PUT", path, token, body(1, "Updated")).statusCode()).isEqualTo(200)
        // Stale version must fail even when the requested fields already match.
        assertThat(request("PUT", path, token, body(1, "Updated")).statusCode()).isEqualTo(409)
        assertThat(request("DELETE", "$path?version=1", token).statusCode()).isEqualTo(409)
        assertThat(number(dto(request("GET", path, token)), "version")).isEqualTo(2)
        assertThat(history(id)).hasSize(1)
    }

    @Test
    fun `delete of an unchanged item archives version one`() {
        val token = login(createUser())
        val id = number(dto(request("POST", token = token, body = body())), "id")
        assertThat(request("DELETE", "/api/wardrobe/items/$id?version=1", token).statusCode()).isEqualTo(204)
        assertThat(history(id)).containsExactly(listOf(1L, "Shirt", 1L, "White", "Cotton",
            TestTimeConfiguration.FIXED_TIME, TestTimeConfiguration.FIXED_TIME))
    }

    @Test
    fun `owners only see their own items and foreign and missing IDs return the same status`() {
        val first = createUser()
        val second = createUser()
        val firstToken = login(first)
        val secondToken = login(second)
        val firstItem = dto(request("POST", token = firstToken,
            body = body().dropLast(1) + ",\"ownerId\":${second.id}}"))
        val secondItem = dto(request("POST", token = secondToken, body = body(name = "Second")))
        val id = number(firstItem, "id")
        assertThat(rows(request("GET", token = firstToken))).containsExactly(firstItem)
        assertThat(rows(request("GET", token = secondToken))).containsExactly(secondItem)
        assertThat(jdbc.queryForObject("SELECT owner_id FROM wardrobe_item WHERE id = ?", Long::class.java, id))
            .isEqualTo(first.id)
        for (target in listOf(id, Long.MAX_VALUE)) {
            val path = "/api/wardrobe/items/$target"
            assertThat(request("GET", path, secondToken).statusCode()).isEqualTo(404)
            assertThat(request("PUT", path, secondToken, body(1, categoryId = Long.MAX_VALUE)).statusCode()).isEqualTo(404)
            assertThat(request("DELETE", "$path?version=1", secondToken).statusCode()).isEqualTo(404)
        }
        assertThat(dto(request("GET", "/api/wardrobe/items/$id", firstToken))).isEqualTo(firstItem)
        assertThat(history(id)).isEmpty()
    }

    @Test
    fun `every endpoint requires an active USER`() {
        val owner = createUser()
        val ownerToken = login(owner)
        val id = number(dto(request("POST", token = ownerToken, body = body())), "id")
        val stylistToken = login(createUser(UserRole.STYLIST))
        val adminToken = login(createUser(UserRole.ADMIN))
        users.changeRoleAndStatus(owner.id!!, owner.version, owner.role, UserStatus.BLOCKED)
        for ((token, expected) in listOf(null to 401, ownerToken to 401, stylistToken to 403, adminToken to 403)) {
            assertThat(request("GET", token = token).statusCode()).isEqualTo(expected)
            assertThat(request("GET", "/api/wardrobe/items/$id", token).statusCode()).isEqualTo(expected)
            assertThat(request("POST", token = token, body = body()).statusCode()).isEqualTo(expected)
            assertThat(request("PUT", "/api/wardrobe/items/$id", token, body(1)).statusCode()).isEqualTo(expected)
            assertThat(request("DELETE", "/api/wardrobe/items/$id?version=1", token).statusCode()).isEqualTo(expected)
        }
        assertThat(history(id)).isEmpty()
    }

    @Test
    fun `required fields category and versions are validated without changing current or history`() {
        val token = login(createUser())
        val original = dto(request("POST", token = token, body = body()))
        val id = number(original, "id")
        for (invalid in listOf("{", "{}", body(name = " "), body(name = "x".repeat(301)), body(color = ""), body(material = " "),
            body(categoryId = 0), body(categoryId = -1), body(categoryId = Long.MAX_VALUE),
            body().replace("\"Shirt\"", "null"), body().replace("\"Cotton\"", "null"),
            body().replace("\"White\"", "null"), body().replace("\"categoryId\":1", "\"categoryId\":null"),
        )) {
            assertThat(request("POST", token = token, body = invalid).statusCode()).describedAs(invalid).isEqualTo(400)
            val updateBody = if (invalid.startsWith("{") && invalid.endsWith("}")) {
                invalid.dropLast(1) + (if (invalid == "{}") "" else ",") + "\"version\":1}"
            } else invalid
            assertThat(request("PUT", "/api/wardrobe/items/$id", token, updateBody).statusCode())
                .describedAs(updateBody).isEqualTo(400)
        }
        for (invalid in listOf(body(), body(0), body(-1), body(1).replace("\"version\":1", "\"version\":null"))) {
            assertThat(request("PUT", "/api/wardrobe/items/$id", token, invalid).statusCode()).isEqualTo(400)
        }
        for (query in listOf("", "?version=0", "?version=-1", "?version=abc", "?version=9223372036854775808")) {
            assertThat(request("DELETE", "/api/wardrobe/items/$id$query", token).statusCode()).isEqualTo(400)
        }
        assertThat(dto(request("GET", "/api/wardrobe/items/$id", token))).isEqualTo(original)
        assertThat(history(id)).isEmpty()
    }

    @Test
    fun `text values are preserved at the name limit without limiting color or material`() {
        val token = login(createUser())
        val name = "  Shirt " + "x".repeat(292)
        val color = " White " + "x".repeat(301)
        val material = "cOtToN" + "x".repeat(301)
        val response = request("POST", token = token, body = body(name = name, color = color, material = material))
        assertThat(response.statusCode()).isEqualTo(201)
        val item = dto(response)
        assertThat(item["name"]).isEqualTo(name)
        assertThat(item["color"]).isEqualTo(color)
        assertThat(item["material"]).isEqualTo(material)
        val updated = request("PUT", "/api/wardrobe/items/${item["id"]}", token,
            body(1, name.reversed(), color = color, material = material))
        assertThat(updated.statusCode()).isEqualTo(200)
        assertThat(dto(updated)["name"]).isEqualTo(name.reversed())
    }

    @Test
    fun `legacy longer names remain readable and are archived intact on update`() {
        val owner = createUser()
        val token = login(owner)
        val id = number(dto(request("POST", token = token, body = body())), "id")
        val legacyName = "x".repeat(301)
        jdbc.update("UPDATE wardrobe_item SET name = ? WHERE id = ?", legacyName, id)
        assertThat(dto(request("GET", "/api/wardrobe/items/$id", token))["name"]).isEqualTo(legacyName)
        assertThat(request("PUT", "/api/wardrobe/items/$id", token, body(1)).statusCode()).isEqualTo(200)
        assertThat(history(id).single()[1]).isEqualTo(legacyName)
    }

    @Test
    fun `list defaults to fifty items in ID order with bounded subsequent pages`() {
        val owner = createUser()
        val token = login(owner)
        jdbc.update("""
            INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version, created_at, modified_at)
            SELECT ?, 1, 'Shirt ' || n, 'White', 'Cotton', 1, ?, ? FROM generate_series(1, 55) n
        """.trimIndent(), owner.id, Timestamp.from(TestTimeConfiguration.FIXED_TIME), Timestamp.from(TestTimeConfiguration.FIXED_TIME))
        val response = request("GET", token = token)
        val first = rows(response)
        val second = rows(request("GET", "/api/wardrobe/items?page=1&size=50", token))
        assertThat(first).hasSize(50)
        assertThat(second).hasSize(5)
        val expected = jdbc.queryForList("SELECT id FROM wardrobe_item WHERE owner_id = ? ORDER BY id", Long::class.java, owner.id)
        assertThat((first + second).map { number(it, "id") }).isEqualTo(expected)
        assertThat(response.headers().allValues("X-Total-Count")).isEmpty()
        assertThat(rows(request("GET", "/api/wardrobe/items?page=1&size=1", token))).containsExactly(first[1])
        assertThat(rows(request("GET", "/api/wardrobe/items?page=2&size=50", token))).isEmpty()
        assertThat(rows(request("GET", "/api/wardrobe/items?page=${Int.MAX_VALUE}&size=1", token))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["page=-1", "size=0", "size=-1", "size=51", "page=abc", "size=abc",
        "page=1.5", "size=1.5", "page=2147483648", "size=2147483648", "page=2147483647&size=2"])
    fun `invalid pages return bad request`(query: String) {
        assertThat(request("GET", "/api/wardrobe/items?$query", login(createUser())).statusCode()).isEqualTo(400)
    }

    private fun createUser(role: UserRole = UserRole.USER): AppUser =
        users.create(UUID.randomUUID().toString(), requireNotNull(passwords.encode(PASSWORD)), role)

    private fun login(user: AppUser): String {
        val response = request("POST", "/api/auth/login", body = "{\"login\":\"${user.login}\",\"password\":\"$PASSWORD\"}")
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$.accessToken")
    }

    private fun body(version: Long? = null, name: String = "Shirt", categoryId: Long = 1,
        color: String = "White", material: String = "Cotton"): String =
        """{"name":"$name","categoryId":$categoryId,"color":"$color","material":"$material"${version?.let { ",\"version\":$it" } ?: ""}}"""

    private fun request(method: String, path: String = "/api/wardrobe/items", token: String? = null,
        body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun dto(response: HttpResponse<String>): Map<String, Any> =
        JsonPath.read<Map<String, Any>>(response.body(), "$").also(::assertFields)

    private fun rows(response: HttpResponse<String>): List<Map<String, Any>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read<List<Map<String, Any>>>(response.body(), "$").onEach(::assertFields)
    }

    private fun assertFields(row: Map<String, Any>) {
        assertThat(row.keys).containsExactlyInAnyOrder("id", "name", "categoryId", "color", "material", "version", "createdAt", "modifiedAt")
    }

    private fun number(row: Map<String, Any>, key: String): Long = (row.getValue(key) as Number).toLong()

    private fun history(id: Long): List<List<Any>> = jdbc.query(
        "SELECT * FROM wardrobe_item_history WHERE wardrobe_item_id = ? ORDER BY version",
        { row, _ -> listOf(row.getLong("version"), row.getString("name"), row.getLong("category_id"),
            row.getString("color"), row.getString("material"), row.getTimestamp("modified_at").toInstant(),
            row.getTimestamp("archived_at").toInstant()) }, id,
    )

    companion object {
        private const val PASSWORD = "wardrobe-test-password"

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
