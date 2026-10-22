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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class StylistWardrobeIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val passwordHash = "!"
    private val photoBytes = byteArrayOf(0, 1, 2, -1, 13, 10)

    @BeforeEach
    fun storageOutsideTransactions() {
        doAnswer {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                photoBytes
            }
            .`when`(storage)
            .get(anyString(), anyLong())
    }

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `stylist reads the same private wardrobe and photo DTOs and bytes without modifying history`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val stylistActorId = actorId(stylist)
        val item = create(ownerActorId)
        val photo = photo(item)
        grant(ownerActorId, stylist)
        val base = wardrobe(owner.id!!)
        for (suffix in listOf("", "/$item", "/$item/photos")) {

            // when
            val own = request("GET", "$ITEMS$suffix", ownerActorId)

            // when
            val shared = request("GET", "$base$suffix", stylistActorId)

            // then
            assertThat(shared.statusCode()).isEqualTo(200)
            assertThat(shared.body()).isEqualTo(own.body())
        }

        // when
        val content = request("GET", "$base/$item/photos/$photo/content", stylistActorId)

        // then
        assertThat(content.statusCode()).isEqualTo(200)
        assertThat(content.body()).isEqualTo(photoBytes)
        assertThat(content.headers().firstValue("Content-Type")).hasValue("image/png")
        assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM wardrobe_item WHERE id = ?",
                    Long::class.java,
                    item,
                ),
            )
            .isEqualTo(1)
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM wardrobe_item_history WHERE wardrobe_item_id = ?",
                    Long::class.java,
                    item,
                ),
            )
            .isZero()
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM app_user_history WHERE user_id IN (?, ?)",
                    Long::class.java,
                    owner.id,
                    stylist.id,
                ),
            )
            .isZero()

        // given: storage fails on the next download
        doAnswer { throw PhotoStorageUnavailableException() }
            .`when`(storage)
            .get(anyString(), anyLong())

        // when
        val unavailable = request("GET", "$base/$item/photos/$photo/content", stylistActorId)

        // then
        assertThat(unavailable.statusCode()).isEqualTo(503)
        assertThat(unavailable.body()).isEmpty()
    }

    @Test
    fun `shared wardrobe list retains pagination bounds and owner filtering`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val actorId = actorId(stylist)
        grant(actorId(owner), stylist)
        val now = Timestamp.from(TestTimeConfiguration.FIXED_TIME)
        jdbc.update(
            """
            INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version, created_at, modified_at)
            SELECT ?, 1, 'Shirt ' || n, 'White', 'Cotton', 1, ?, ? FROM generate_series(1, 55) n
            """
                .trimIndent(),
            owner.id,
            now,
            now,
        )
        create(actorId(user()))
        val path = wardrobe(owner.id!!)

        // when
        val firstResponse = request("GET", path, actorId)
        val first = rows(firstResponse)
        val second = rows(request("GET", "$path?page=1&size=50", actorId))

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
        assertThat(firstResponse.headers().allValues("X-Total-Count")).isEmpty()

        // when
        val singleItemPage = rows(request("GET", "$path?page=1&size=1", actorId))

        // then
        assertThat(singleItemPage).containsExactly(first[1])

        // when
        val emptyPage = rows(request("GET", "$path?page=2&size=50", actorId))

        // then
        assertThat(emptyPage).isEmpty()

        // when
        val maximumOffsetPage = rows(request("GET", "$path?page=${Int.MAX_VALUE}&size=1", actorId))

        // then
        assertThat(maximumOffsetPage).isEmpty()
        for (query in INVALID_PAGES) {

            // given: invalid pagination

            // when
            val invalidPageStatus = request("GET", "$path?$query", actorId).statusCode()

            // then
            assertThat(invalidPageStatus).isEqualTo(400)
        }
    }

    @Test
    fun `missing access and mismatched owner item or photo return empty 404 without S3`() {

        // given
        val owner = user()
        val other = user()
        val stylist = user(UserRole.STYLIST)
        val actorId = actorId(stylist)
        val ownerActorId = actorId(owner)
        val item = create(ownerActorId)
        val second = create(ownerActorId)
        val otherItem = create(actorId(other))
        val photo = photo(item)
        val otherPhoto = photo(otherItem)
        for (ownerId in listOf(owner.id!!, other.id!!, Long.MAX_VALUE)) {
            readPaths(ownerId, item, photo).forEach {

                // when
                val missingAccess = request("GET", it, actorId)

                // then
                assertNotFound(missingAccess)
            }
        }

        // given
        grant(ownerActorId, stylist)
        for (itemId in listOf(otherItem, Long.MAX_VALUE)) {
            readPaths(owner.id!!, itemId, photo).drop(1).forEach {

                // when
                val foreignItem = request("GET", it, actorId)

                // then
                assertNotFound(foreignItem)
            }
        }
        for ((itemId, photoId) in
            listOf(second to photo, item to otherPhoto, item to Long.MAX_VALUE)) {

            // when
            val mismatchedPhoto =
                request("GET", "${wardrobe(owner.id!!)}/$itemId/photos/$photoId/content", actorId)

            // then
            assertNotFound(mismatchedPhoto)
        }
        readPaths(other.id!!, item, photo).forEach {

            // when
            val otherOwner = request("GET", it, actorId)

            // then
            assertNotFound(otherOwner)
        }

        // when
        val revokeStatus = request("DELETE", "$ACCESS/${stylist.id}", ownerActorId).statusCode()

        // then
        assertThat(revokeStatus).isEqualTo(204)
        readPaths(owner.id!!, item, photo).forEach {

            // when
            val revokedAccess = request("GET", it, actorId)

            // then
            assertNotFound(revokedAccess)
        }
        verifyNoInteractions(storage)
    }

    @Test
    fun `stylist has no client mutation routes and personal API hides foreign items`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val actorId = actorId(stylist)
        val item = create(ownerActorId)
        val photo = photo(item)
        grant(ownerActorId, stylist)
        val base = wardrobe(owner.id!!)
        for ((method, suffix) in
            listOf(
                "POST" to "",
                "PUT" to "/$item",
                "DELETE" to "/$item?version=1",
                "POST" to "/$item/photos",
                "DELETE" to "/$item/photos/$photo",
            )) {
            val upload = method == "POST" && suffix.endsWith("/photos")
            val body =
                when {
                    upload ->
                        "--photo-boundary\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"photo.png\"\r\n" +
                            "Content-Type: image/png\r\n\r\nbytes\r\n--photo-boundary--\r\n"
                    method == "DELETE" -> null
                    else -> ITEM.dropLast(1) + ",\"version\":1}"
                }
            val contentType =
                if (upload) "multipart/form-data; boundary=photo-boundary" else "application/json"

            // when
            val sharedMutationStatus =
                request(method, "$base$suffix", actorId, body, contentType).statusCode()

            // then
            assertThat(sharedMutationStatus).isIn(404, 405)

            // when
            val personalMutationStatus =
                request(method, "$ITEMS$suffix", actorId, body, contentType).statusCode()

            // then
            assertThat(personalMutationStatus)
                .isEqualTo(if (method == "POST" && suffix.isEmpty()) 201 else 404)
        }

        // when
        val ownerReadStatus = request("GET", "$ITEMS/$item", ownerActorId).statusCode()

        // then
        assertThat(ownerReadStatus).isEqualTo(200)

        // when
        val preservedPhotos = rows(request("GET", "$ITEMS/$item/photos", ownerActorId))

        // then
        assertThat(preservedPhotos).hasSize(1)
        verifyNoInteractions(storage)
    }

    private fun user(
        role: UserRole = UserRole.USER,
        status: UserStatus = UserStatus.ACTIVE,
    ): AppUser = jdbc.insertUser(UUID.randomUUID().toString(), passwordHash, role, status)

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun grant(actorId: String, stylist: AppUser) {

        // when
        val grantStatus = request("PUT", "$ACCESS/${stylist.id}", actorId).statusCode()

        // then
        assertThat(grantStatus).isEqualTo(204)
    }

    private fun create(actorId: String): Long {

        // when
        val response = request("POST", ITEMS, actorId, ITEM)

        // then
        assertThat(response.statusCode()).isEqualTo(201)
        return number(JsonPath.read(String(response.body()), "$"), "id")
    }

    private fun photo(itemId: Long): Long =
        jdbc.queryForObject(
            """
            INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
            VALUES (?, ?, 'image/png', ?, ?) RETURNING id
            """
                .trimIndent(),
            Long::class.java,
            itemId,
            UUID.randomUUID().toString(),
            photoBytes.size,
            Timestamp.from(TestTimeConfiguration.FIXED_TIME),
        )!!

    private fun wardrobe(ownerId: Long) = "$CLIENTS/$ownerId/wardrobe/items"

    private fun readPaths(ownerId: Long, itemId: Long, photoId: Long): List<String> {
        val base = wardrobe(ownerId)
        return listOf(
            base,
            "$base/$itemId",
            "$base/$itemId/photos",
            "$base/$itemId/photos/$photoId/content",
        )
    }

    private fun assertNotFound(response: HttpResponse<ByteArray>) {

        // then
        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.body()).isEmpty()
    }

    private fun rows(response: HttpResponse<ByteArray>): List<Map<String, Any>> {

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(String(response.body()), "$")
    }

    private fun number(row: Map<String, Any>, key: String) = (row.getValue(key) as Number).toLong()

    private fun request(
        method: String,
        path: String,
        actorId: String? = null,
        body: String? = null,
        contentType: String = "application/json",
    ): HttpResponse<ByteArray> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString)
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
        if (body != null) request.header("Content-Type", contentType)
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    companion object {
        private const val ACCESS = "/api/me/stylist-access"
        private const val CLIENTS = "/api/stylist/clients"
        private const val ITEMS = "/api/wardrobe/items"
        private const val ITEM =
            """{"name":"Shirt","categoryId":1,"color":"White","material":"Cotton"}"""
        private val INVALID_PAGES =
            listOf(
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
            )
    }
}
