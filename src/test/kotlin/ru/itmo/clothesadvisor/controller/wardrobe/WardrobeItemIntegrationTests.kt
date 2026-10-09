package ru.itmo.clothesadvisor.controller.wardrobe

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
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
import org.springframework.dao.DataIntegrityViolationException
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
class WardrobeItemIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var users: AppUserService

    @Autowired private lateinit var jdbc: JdbcTemplate

    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun closeHttpClient() {
        client.close()
    }

    @Test
    fun `CRUD returns exact DTOs and archives previous and final values`() {

        // given
        val owner = createUser()
        val actorId = actorId(owner)

        // when
        val created = request("POST", actorId = actorId, body = body())

        // then
        assertThat(created.statusCode()).isEqualTo(201)
        val original = dto(created)
        val id = number(original, "id")
        val path = "/api/wardrobe/items/$id"
        assertThat(created.headers().firstValue("Location")).hasValue(path)
        assertThat(number(original, "version")).isEqualTo(1)
        assertThat(original["createdAt"])
            .isEqualTo(
                jdbc
                    .queryForObject(
                        "SELECT created_at FROM wardrobe_item WHERE id = ?",
                        Timestamp::class.java,
                        id,
                    )!!
                    .toInstant()
                    .toString(),
            )
        assertThat(original["modifiedAt"]).isEqualTo(original["createdAt"])

        // when
        val fetched = request("GET", path, actorId)

        // then
        assertThat(fetched.statusCode()).isEqualTo(200)
        assertThat(dto(fetched)).isEqualTo(original)

        // when
        val listedItems = rows(request("GET", actorId = actorId))

        // then
        assertThat(listedItems).containsExactly(original)
        assertThat(history(id)).isEmpty()

        // given
        val earlier = TestTimeConfiguration.FIXED_TIME.minusSeconds(10)
        jdbc.update(
            "UPDATE wardrobe_item SET created_at = ?, modified_at = ? WHERE id = ?",
            Timestamp.from(earlier),
            Timestamp.from(earlier),
            id,
        )

        // when
        val changedResponse = request("PUT", path, actorId, body(1, "Jacket", 2, "Blue", "Wool"))

        // then
        assertThat(changedResponse.statusCode()).isEqualTo(200)
        val changed = dto(changedResponse)
        assertThat(number(changed, "version")).isEqualTo(2)
        assertThat(changed["createdAt"]).isEqualTo(earlier.toString())
        val changedAt =
            jdbc
                .queryForObject(
                    "SELECT modified_at FROM wardrobe_item WHERE id = ?",
                    Timestamp::class.java,
                    id,
                )!!
                .toInstant()
        assertThat(changed["modifiedAt"]).isEqualTo(changedAt.toString())
        assertThat(changed["name"]).isEqualTo("Jacket")
        assertThat(number(changed, "categoryId")).isEqualTo(2)
        assertThat(changed["color"]).isEqualTo("Blue")
        assertThat(changed["material"]).isEqualTo("Wool")
        assertThat(
                jdbc.queryForObject(
                    "SELECT owner_id FROM wardrobe_item WHERE id = ?",
                    Long::class.java,
                    id,
                ),
            )
            .isEqualTo(owner.id)
        assertThat(history(id))
            .containsExactly(
                listOf(
                    1L,
                    "Shirt",
                    1L,
                    "White",
                    "Cotton",
                    earlier,
                    changedAt,
                ),
            )

        // when
        val deleted = request("DELETE", "$path?version=2", actorId)

        // then
        assertThat(deleted.statusCode()).isEqualTo(204)
        assertThat(deleted.body()).isEmpty()

        // when
        val deletedItemStatus = request("GET", path, actorId).statusCode()

        // then
        assertThat(deletedItemStatus).isEqualTo(404)

        // when
        val remainingItems = rows(request("GET", actorId = actorId))

        // then
        assertThat(remainingItems).isEmpty()
        assertThat(history(id))
            .containsExactly(
                listOf(1L, "Shirt", 1L, "White", "Cotton", earlier, changedAt),
                listOf(2L, "Jacket", 2L, "Blue", "Wool", changedAt, history(id).last().last()),
            )
    }

    @Test
    fun `unchanged PUT stays at the same version and stale requests cannot mutate it`() {

        // given
        val actorId = actorId(createUser())
        val original = dto(request("POST", actorId = actorId, body = body()))
        val id = number(original, "id")
        val path = "/api/wardrobe/items/$id"

        // when
        val unchanged = request("PUT", path, actorId, body(1))

        // then
        assertThat(unchanged.statusCode()).isEqualTo(200)
        assertThat(dto(unchanged)).isEqualTo(original)
        assertThat(history(id)).isEmpty()

        // when
        val updateStatus = request("PUT", path, actorId, body(1, "Updated")).statusCode()

        // then
        assertThat(updateStatus).isEqualTo(200)
        // Stale version must fail even when the requested fields already match.

        // when
        val staleUpdateStatus = request("PUT", path, actorId, body(1, "Updated")).statusCode()

        // then
        assertThat(staleUpdateStatus).isEqualTo(409)

        // when
        val staleDeleteStatus = request("DELETE", "$path?version=1", actorId).statusCode()

        // then
        assertThat(staleDeleteStatus).isEqualTo(409)

        // when
        val currentVersion = number(dto(request("GET", path, actorId)), "version")

        // then
        assertThat(currentVersion).isEqualTo(2)
        assertThat(history(id)).hasSize(1)
    }

    @Test
    fun `delete of an unchanged item archives version one`() {

        // given
        val actorId = actorId(createUser())
        val created = dto(request("POST", actorId = actorId, body = body()))
        val id = number(created, "id")

        // when
        val deleteStatus =
            request("DELETE", "/api/wardrobe/items/$id?version=1", actorId).statusCode()

        // then
        assertThat(deleteStatus).isEqualTo(204)
        assertThat(history(id))
            .containsExactly(
                listOf(
                    1L,
                    "Shirt",
                    1L,
                    "White",
                    "Cotton",
                    java.time.Instant.parse(created["modifiedAt"].toString()),
                    history(id).single().last(),
                ),
            )
    }

    @Test
    fun `owners only see their own items and foreign and missing IDs return the same status`() {

        // given
        val first = createUser()
        val second = createUser()
        val firstActorId = actorId(first)
        val secondActorId = actorId(second)
        val firstItem =
            dto(
                request(
                    "POST",
                    actorId = firstActorId,
                    body = body().dropLast(1) + ",\"ownerId\":${second.id}}",
                ),
            )
        val secondItem = dto(request("POST", actorId = secondActorId, body = body(name = "Second")))
        val id = number(firstItem, "id")

        // when
        val firstOwnerItems = rows(request("GET", actorId = firstActorId))

        // then
        assertThat(firstOwnerItems).containsExactly(firstItem)

        // when
        val secondOwnerItems = rows(request("GET", actorId = secondActorId))

        // then
        assertThat(secondOwnerItems).containsExactly(secondItem)
        assertThat(
                jdbc.queryForObject(
                    "SELECT owner_id FROM wardrobe_item WHERE id = ?",
                    Long::class.java,
                    id,
                ),
            )
            .isEqualTo(first.id)
        for (target in listOf(id, Long.MAX_VALUE)) {

            // given: a foreign or missing item ID
            val path = "/api/wardrobe/items/$target"

            // when
            val readStatus = request("GET", path, secondActorId).statusCode()

            // then
            assertThat(readStatus).isEqualTo(404)

            // when
            val updateStatus =
                request("PUT", path, secondActorId, body(1, categoryId = Long.MAX_VALUE))
                    .statusCode()

            // then
            assertThat(updateStatus).isEqualTo(404)

            // when
            val deleteStatus = request("DELETE", "$path?version=1", secondActorId).statusCode()

            // then
            assertThat(deleteStatus).isEqualTo(404)
        }

        // when
        val preservedItem = dto(request("GET", "/api/wardrobe/items/$id", firstActorId))

        // then
        assertThat(preservedItem).isEqualTo(firstItem)
        assertThat(history(id)).isEmpty()
    }

    @Test
    fun `every endpoint requires an active actor`() {

        // given
        val owner = createUser()
        val ownerActorId = actorId(owner)
        val id = number(dto(request("POST", actorId = ownerActorId, body = body())), "id")
        users.changeRoleAndStatus(owner.id!!, owner.version, owner.role, UserStatus.BLOCKED)
        for ((actorId, expected) in listOf(null to 400, ownerActorId to 403)) {

            // when
            val listStatus = request("GET", actorId = actorId).statusCode()

            // then
            assertThat(listStatus).isEqualTo(expected)

            // when
            val readStatus = request("GET", "/api/wardrobe/items/$id", actorId).statusCode()

            // then
            assertThat(readStatus).isEqualTo(expected)

            // when
            val createStatus = request("POST", actorId = actorId, body = body()).statusCode()

            // then
            assertThat(createStatus).isEqualTo(expected)

            // when
            val updateStatus =
                request("PUT", "/api/wardrobe/items/$id", actorId, body(1)).statusCode()

            // then
            assertThat(updateStatus).isEqualTo(expected)

            // when
            val deleteStatus =
                request("DELETE", "/api/wardrobe/items/$id?version=1", actorId).statusCode()

            // then
            assertThat(deleteStatus).isEqualTo(expected)
        }
        assertThat(history(id)).isEmpty()
    }

    @Test
    fun `required fields category and versions are validated without changing current or history`() {

        // given
        val actorId = actorId(createUser())
        val original = dto(request("POST", actorId = actorId, body = body()))
        val id = number(original, "id")
        for (invalid in
            listOf(
                "{",
                "{}",
                body(name = " "),
                body(name = "x".repeat(301)),
                body(color = ""),
                body(material = " "),
                body(color = "x".repeat(101)),
                body(material = "x".repeat(101)),
                body(categoryId = 0),
                body(categoryId = -1),
                body(categoryId = Long.MAX_VALUE),
                body().replace("\"Shirt\"", "null"),
                body().replace("\"Cotton\"", "null"),
                body().replace("\"White\"", "null"),
                body().replace("\"categoryId\":1", "\"categoryId\":null"),
            )) {

            // when
            val createStatus = request("POST", actorId = actorId, body = invalid).statusCode()

            // then
            assertThat(createStatus).describedAs(invalid).isEqualTo(400)

            // given
            val updateBody =
                if (invalid.startsWith("{") && invalid.endsWith("}")) {
                    invalid.dropLast(1) + (if (invalid == "{}") "" else ",") + "\"version\":1}"
                } else invalid

            // when
            val updateStatus =
                request("PUT", "/api/wardrobe/items/$id", actorId, updateBody).statusCode()

            // then
            assertThat(updateStatus).describedAs(updateBody).isEqualTo(400)
        }
        for (invalid in
            listOf(
                body(),
                body(0),
                body(-1),
                body(1).replace("\"version\":1", "\"version\":null"),
            )) {

            // when
            val invalidVersionStatus =
                request("PUT", "/api/wardrobe/items/$id", actorId, invalid).statusCode()

            // then
            assertThat(invalidVersionStatus).isEqualTo(400)
        }
        for (query in
            listOf(
                "",
                "?version=0",
                "?version=-1",
                "?version=abc",
                "?version=9223372036854775808",
            )) {

            // when
            val invalidVersionStatus =
                request("DELETE", "/api/wardrobe/items/$id$query", actorId).statusCode()

            // then
            assertThat(invalidVersionStatus).isEqualTo(400)
        }

        // when
        val preservedItem = dto(request("GET", "/api/wardrobe/items/$id", actorId))

        // then
        assertThat(preservedItem).isEqualTo(original)
        assertThat(history(id)).isEmpty()
    }

    @Test
    fun `text values are preserved at their exact length limits`() {

        // given
        val actorId = actorId(createUser())
        val name = "  Shirt " + "x".repeat(292)
        val color = " White " + "x".repeat(93)
        val material = "cOtToN" + "x".repeat(94)

        // when
        val response =
            request(
                "POST",
                actorId = actorId,
                body = body(name = name, color = color, material = material),
            )

        // then
        assertThat(response.statusCode()).isEqualTo(201)
        val item = dto(response)
        assertThat(item["name"]).isEqualTo(name)
        assertThat(item["color"]).isEqualTo(color)
        assertThat(item["material"]).isEqualTo(material)

        // when
        val updated =
            request(
                "PUT",
                "/api/wardrobe/items/${item["id"]}",
                actorId,
                body(1, name.reversed(), color = color, material = material),
            )

        // then
        assertThat(updated.statusCode()).isEqualTo(200)
        assertThat(dto(updated)["name"]).isEqualTo(name.reversed())
    }

    @Test
    fun `database rejects invalid current and history text without changing data`() {

        // given
        val owner = createUser()
        val actorId = actorId(owner)
        val id = number(dto(request("POST", actorId = actorId, body = body())), "id")
        for ((column, value) in
            listOf(
                "name" to "x".repeat(301),
                "color" to "x".repeat(101),
                "material" to "x".repeat(101),
                "name" to " \t\n",
            )) {

            // when
            val invalidCurrentText = catchThrowable {
                jdbc.update("UPDATE wardrobe_item SET $column = ? WHERE id = ?", value, id)
            }

            // then
            assertThat(invalidCurrentText).isInstanceOf(DataIntegrityViolationException::class.java)
        }

        // when
        val invalidHistoryText = catchThrowable {
            jdbc.update(
                """
            INSERT INTO wardrobe_item_history (wardrobe_item_id, version, name, category_id, color, material, modified_at)
            SELECT id, version, ?, category_id, color, material, modified_at FROM wardrobe_item WHERE id = ?
        """,
                "x".repeat(301),
                id,
            )
        }

        // then
        assertThat(invalidHistoryText).isInstanceOf(DataIntegrityViolationException::class.java)

        // when
        val preservedName = dto(request("GET", "/api/wardrobe/items/$id", actorId))["name"]

        // then
        assertThat(preservedName).isEqualTo("Shirt")
        assertThat(history(id)).isEmpty()
    }

    @Test
    fun `list defaults to fifty items in ID order with bounded subsequent pages`() {

        // given
        val owner = createUser()
        val actorId = actorId(owner)
        jdbc.update(
            """
            INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version, created_at, modified_at)
            SELECT ?, 1, 'Shirt ' || n, 'White', 'Cotton', 1, ?, ? FROM generate_series(1, 55) n
            """
                .trimIndent(),
            owner.id,
            Timestamp.from(TestTimeConfiguration.FIXED_TIME),
            Timestamp.from(TestTimeConfiguration.FIXED_TIME),
        )

        // when
        val response = request("GET", actorId = actorId)
        val first = rows(response)
        val second = rows(request("GET", "/api/wardrobe/items?page=1&size=50", actorId))

        // then
        assertThat(first).hasSize(50)
        assertThat(second).hasSize(5)
        val expected =
            jdbc.queryForList(
                "SELECT id FROM wardrobe_item WHERE owner_id = ? ORDER BY id",
                Long::class.java,
                owner.id,
            )
        assertThat((first + second).map { number(it, "id") }).isEqualTo(expected)
        assertThat(response.headers().allValues("X-Total-Count")).isEmpty()

        // when
        val singleItemPage = rows(request("GET", "/api/wardrobe/items?page=1&size=1", actorId))

        // then
        assertThat(singleItemPage).containsExactly(first[1])

        // when
        val emptyPage = rows(request("GET", "/api/wardrobe/items?page=2&size=50", actorId))

        // then
        assertThat(emptyPage).isEmpty()

        // when
        val maximumOffsetPage =
            rows(request("GET", "/api/wardrobe/items?page=${Int.MAX_VALUE}&size=1", actorId))

        // then
        assertThat(maximumOffsetPage).isEmpty()
    }

    @ParameterizedTest
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
                "page=2147483647&size=2",
            ],
    )
    fun `invalid pages return bad request`(query: String) {

        // given
        val actorId = actorId(createUser())

        // when
        val invalidPageStatus = request("GET", "/api/wardrobe/items?$query", actorId).statusCode()

        // then
        assertThat(invalidPageStatus).isEqualTo(400)
    }

    private fun createUser(role: UserRole = UserRole.USER): AppUser =
        jdbc.insertUser(UUID.randomUUID().toString(), "!", role)

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun body(
        version: Long? = null,
        name: String = "Shirt",
        categoryId: Long = 1,
        color: String = "White",
        material: String = "Cotton",
    ): String =
        """{"name":"$name","categoryId":$categoryId,"color":"$color","material":"$material"${version?.let { ",\"version\":$it" } ?: ""}}"""

    private fun request(
        method: String,
        path: String = "/api/wardrobe/items",
        actorId: String? = null,
        body: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString)
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
        if (body != null) request.header("Content-Type", "application/json")
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun dto(response: HttpResponse<String>): Map<String, Any> =
        JsonPath.read<Map<String, Any>>(response.body(), "$").also(::assertFields)

    private fun rows(response: HttpResponse<String>): List<Map<String, Any>> {

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read<List<Map<String, Any>>>(response.body(), "$").onEach(::assertFields)
    }

    private fun assertFields(row: Map<String, Any>) {
        assertThat(row.keys)
            .containsExactlyInAnyOrder(
                "id",
                "name",
                "categoryId",
                "color",
                "material",
                "version",
                "createdAt",
                "modifiedAt",
            )
    }

    private fun number(row: Map<String, Any>, key: String): Long =
        (row.getValue(key) as Number).toLong()

    private fun history(id: Long): List<List<Any>> =
        jdbc.query(
            "SELECT * FROM wardrobe_item_history WHERE wardrobe_item_id = ? ORDER BY version",
            { row, _ ->
                listOf(
                    row.getLong("version"),
                    row.getString("name"),
                    row.getLong("category_id"),
                    row.getString("color"),
                    row.getString("material"),
                    row.getTimestamp("modified_at").toInstant(),
                    row.getTimestamp("archived_at").toInstant(),
                )
            },
            id,
        )

    companion object {}
}
