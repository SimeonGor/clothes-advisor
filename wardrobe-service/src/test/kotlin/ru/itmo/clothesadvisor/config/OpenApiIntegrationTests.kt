package ru.itmo.clothesadvisor.config

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"],
)
@ActiveProfiles("test", "local")
@Execution(ExecutionMode.SAME_THREAD)
class OpenApiIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun closeHttpClient() = client.close()

    @Test
    fun `generated wardrobe spec retains public requests responses and actor headers`() {
        // given
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/v3/api-docs"))
            .timeout(Duration.ofSeconds(15)).GET().build()
        val publicDictionaries = setOf(
            "/api/wardrobe/categories", "/api/weather/precipitation-types",
        )
        val accountPaths = setOf(
            "/api/admin/users", "/api/admin/users/{id}", "/api/me/stylist-access",
            "/api/me/stylist-access/{stylistId}", "/api/stylist/clients",
        )

        // when
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        val spec = JsonPath.parse(response.body())
        assertThat(spec.read<String>("$.info.title")).isEqualTo("Wardrobe API")
        assertThat(spec.read<List<String>>("$.servers[*].url")).containsExactly("/")
        val paths = spec.read<Map<String, Map<String, Any?>>>("$.paths")
        assertThat(paths.keys).isNotEmpty().allMatch { it.startsWith("/api/") }
            .noneMatch { it.startsWith("/api/recommendation") || it in accountPaths }
        assertThat(paths.keys).containsAll(publicDictionaries)
        val operationIds = spec.read<List<String>>("$.paths.*.*.operationId")
        assertThat(operationIds).hasSize(paths.values.sumOf { it.size })
            .doesNotHaveDuplicates().allMatch { it.isNotBlank() }
        paths.forEach { (path, operations) ->
            operations.keys.forEach { method ->
                val headers = spec.read<List<Map<String, Any?>>>(
                    "$.paths['$path'].$method.parameters[?(@.name == 'X-User-Id')]",
                )
                if (path in publicDictionaries) {
                    assertThat(headers).`as`("$method $path has no actor").isEmpty()
                } else {
                    assertThat(headers).`as`("$method $path requires one actor").hasSize(1)
                    assertThat(headers.single()).containsEntry("in", "header")
                        .containsEntry("required", true)
                }
            }
        }

        val schemas = spec.read<Map<String, Any?>>("$.components.schemas")
        val references = spec.read<List<String>>("$..['\$ref']")
        assertThat(references).isNotEmpty()
        references.forEach { reference ->
            assertThat(reference).startsWith("#/components/schemas/")
            assertThat(schemas).containsKey(reference.removePrefix("#/components/schemas/"))
        }
        assertThat(schemas).doesNotContainKeys("CurrentUser", "InternalUserResponse")

        val requests = "$.paths['/api/me/stylist-requests']"
        assertThat(spec.read<Map<String, Any?>>("$requests.post.responses").keys)
            .contains("201", "400", "403", "404", "409", "503").doesNotContain("200")
        assertThat(spec.read<String>("$requests.post.responses['201'].headers.Location.schema.format"))
            .isEqualTo("uri")
        assertThat(spec.read<List<String>>("$requests.post.responses['201'].content.*.schema['\$ref']"))
            .containsExactly("#/components/schemas/StylistRequestResponse")
        assertThat(spec.read<String>("$requests.post.responses['409'].content['application/json'].schema['\$ref']"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")
        listOf("/api/me/stylist-requests", "/api/stylist/clients/{ownerId}/requests").forEach { path ->
            assertThat(spec.read<String>("$.paths['$path'].get.responses['200'].headers['X-Total-Count'].schema.format"))
                .isEqualTo("int64")
        }
        assertThat(spec.read<Map<String, Any?>>(
            "$.paths['/api/me/stylist-requests/{id}/cancellation'].put.responses",
        ).keys).contains("200", "409", "503")
        listOf(
            "['/api/me/stylist-requests/{id}'].get",
            "['/api/stylist/clients/{ownerId}/requests/{id}'].get",
            "['/api/me/stylist-requests/{id}/cancellation'].put",
        ).forEach { operation ->
            assertThat(spec.read<List<String>>("$.paths$operation.responses['200'].content.*.schema['\$ref']"))
                .containsExactly("#/components/schemas/StylistRequestResponse")
        }

        val preferences = "$.paths['/api/me/style-preferences']"
        val answer = "$.paths['/api/stylist/clients/{ownerId}/requests/{id}/answer'].post"
        assertThat(spec.read<Map<String, Any?>>("$answer.responses").keys)
            .contains("201", "400", "403", "404", "409", "503").doesNotContain("200")
        assertThat(spec.read<String>("$answer.responses['201'].headers.Location.schema.format")).isEqualTo("uri")
        assertThat(spec.read<List<String>>("$answer.responses['201'].content.*.schema['\$ref']"))
            .containsExactly("#/components/schemas/StylistRequestResponse")
        assertThat(spec.read<String>("$answer.requestBody.content['application/json'].schema['\$ref']"))
            .isEqualTo("#/components/schemas/AnswerStylistRequest")
        assertThat(spec.read<List<String>>("$.components.schemas.StylistRequestResponse.properties.answer..['\$ref']"))
            .containsExactly("#/components/schemas/StylistRequestAnswerResponse")
        assertThat(spec.read<List<String>>("$.components.schemas.StylistRequestResponse.required")).doesNotContain("answer")
        val answerSchema = "$.components.schemas.AnswerStylistRequest.properties"
        assertThat(spec.read<Int>("$answerSchema.name.maxLength")).isEqualTo(300)
        assertThat(spec.read<Int>("$answerSchema.explanation.maxLength")).isEqualTo(2000)
        assertThat(spec.read<Int>("$answerSchema.itemIds.minItems")).isEqualTo(1)
        assertThat(spec.read<Int>("$answerSchema.itemIds.maxItems")).isEqualTo(50)
        assertThat(spec.read<Boolean>("$answerSchema.itemIds.uniqueItems")).isTrue()

        assertThat(paths.getValue("/api/me/style-preferences").keys)
            .containsExactlyInAnyOrder("get", "put")
        assertThat(paths.getValue("/api/stylist/clients/{ownerId}/style-preferences").keys)
            .containsExactly("get")
        assertThat(spec.read<String>("$preferences.put.requestBody.content['application/json'].schema['\$ref']"))
            .isEqualTo("#/components/schemas/PutStylePreferencesRequest")
        val preferencesSchema = "$.components.schemas.PutStylePreferencesRequest"
        listOf("preferredColorsAndMaterials", "avoidances", "additionalWishes").forEach { field ->
            assertThat(spec.read<Int>("$preferencesSchema.properties.$field.maxLength")).isEqualTo(2000)
        }
        assertThat(spec.read<Map<String, Any?>>(preferencesSchema)["required"]).isNull()
        assertThat(spec.read<Map<String, Any?>>("$preferences.put.responses").keys)
            .contains("200", "400", "403", "409", "503")
        assertThat(spec.read<List<String>>("$preferences.get.responses['200'].content.*.schema['\$ref']"))
            .containsExactly("#/components/schemas/StylePreferencesResponse")

        val create = "$.paths['/api/wardrobe/items'].post"
        val content = "$create.requestBody.content"
        assertThat(spec.read<Map<String, Any?>>(content).keys)
            .containsExactlyInAnyOrder("application/json", "multipart/form-data")
        assertThat(spec.read<String>("$content['application/json'].schema['\$ref']"))
            .isEqualTo("#/components/schemas/CreateWardrobeItemRequest")
        val multipart = "$content['multipart/form-data']"
        assertThat(spec.read<String>("$multipart.encoding.item.contentType"))
            .isEqualTo("application/json")
        val multipartSchema = "$multipart.schema"
        assertThat(spec.read<List<String>>("$multipartSchema.required")).contains("item")
        assertThat(spec.read<String>("$multipartSchema.properties.item['\$ref']"))
            .isEqualTo("#/components/schemas/CreateWardrobeItemRequest")
        assertThat(spec.read<String>("$multipartSchema.properties.photos.type")).isEqualTo("array")
        assertThat(spec.read<Map<String, Any?>>("$multipartSchema.properties.photos.items"))
            .containsEntry("type", "string").containsEntry("format", "binary")
        val addPhotos = "$.paths['/api/wardrobe/items/{itemId}/photos'].post.requestBody"
        val uploadedPhotos =
            "$addPhotos.content['multipart/form-data'].schema.properties.photos"
        assertThat(spec.read<String>("$uploadedPhotos.type")).isEqualTo("array")
        assertThat(spec.read<Map<String, Any?>>("$uploadedPhotos.items"))
            .containsEntry("type", "string").containsEntry("format", "binary")
        assertThat(spec.read<String>(
            "$create.responses['400'].content['application/json'].schema['\$ref']",
        )).isEqualTo("#/components/schemas/ApiErrorResponse")
        assertThat(spec.read<String>("$create.responses['201'].headers.Location.schema.format"))
            .isEqualTo("uri")

        val outfitList = "$.paths['/api/outfits'].get.responses['200']"
        assertThat(spec.read<String>("$outfitList.headers['X-Total-Count'].schema.type"))
            .isEqualTo("integer")
        assertThat(spec.read<String>("$outfitList.headers['X-Total-Count'].schema.format"))
            .isEqualTo("int64")
        val photoPath = "/api/wardrobe/items/{itemId}/photos/{photoId}/content"
        val photo = "$.paths['$photoPath'].get.responses['200']"
        assertThat(spec.read<Map<String, Any?>>("$photo.content").keys)
            .containsExactlyInAnyOrder("image/jpeg", "image/png")
        listOf("image/jpeg", "image/png").forEach { mediaType ->
            assertThat(spec.read<String>("$photo.content['$mediaType'].schema.type"))
                .isEqualTo("string")
            assertThat(spec.read<String>("$photo.content['$mediaType'].schema.format"))
                .isEqualTo("binary")
        }
        assertThat(spec.read<List<String>>("$photo.headers['Cache-Control'].schema.enum"))
            .containsExactly("no-store")
        assertThat(spec.read<List<String>>("$photo.headers['X-Content-Type-Options'].schema.enum"))
            .containsExactly("nosniff")
    }
}
