package ru.itmo.clothesadvisor.controller.wardrobe

import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.insertUser
import com.jayway.jsonpath.JsonPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any as anyArgument
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verifyNoInteractions
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class WardrobeItemPhotoIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val png = image("png")
    private val jpeg = image("jpeg")

    @BeforeEach
    fun storageOutsideTransactions() {
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            objects[call.getArgument(0)] = call.getArgument(2)
            null
        }.`when`(storage).put(anyString(), anyString(), anyArgument(ByteArray::class.java) ?: byteArrayOf())
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            objects.getValue(call.getArgument(0))
        }.`when`(storage).get(anyString(), anyLong())
    }

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `multipart name accepts 300 and rejects 301 before uploading photos`() {
        val actorId = actorId(user())
        val created = multipart(actorId, item = ITEM.replace("Shirt", "x".repeat(300)))
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(objectBody(created)["name"]).isEqualTo("x".repeat(300))
        val rejected = multipart(actorId, files = listOf(png), item = ITEM.replace("Shirt", "x".repeat(301)))
        assertThat(rejected.statusCode()).isEqualTo(400)
        verifyNoInteractions(storage)
    }

    @Test
    fun `multipart creation append content and deletion preserve item version and history`() {
        val actorId = actorId(user())
        val created = multipart(actorId, files = listOf(png, jpeg), item = ITEM)
        assertThat(created.statusCode()).isEqualTo(201)
        val item = objectBody(created)
        val id = number(item, "id")
        assertThat(created.headers().firstValue("Location")).hasValue("$ITEMS/$id")
        val initial = rows(request("GET", "$ITEMS/$id/photos", actorId))
        assertThat(initial.map { it["contentType"] }).containsExactly("image/png", "image/jpeg")
        assertThat(initial.map { number(it, "id") }).isSorted()
        initial.forEachIndexed { index, photo ->
            assertThat(photo.keys).containsExactlyInAnyOrder("id", "itemId", "contentType", "sizeBytes", "createdAt")
            assertThat(number(photo, "itemId")).isEqualTo(id)
            assertThat(number(photo, "sizeBytes")).isEqualTo(listOf(png, jpeg)[index].size.toLong())
            assertThat(photo["createdAt"]).isEqualTo(jdbc.queryForObject("SELECT created_at FROM wardrobe_item_photo WHERE id = ?", java.sql.Timestamp::class.java, number(photo, "id"))!!.toInstant().toString())
            val content = request("GET", "$ITEMS/$id/photos/${photo["id"]}/content", actorId)
            assertThat(content.statusCode()).isEqualTo(200)
            assertThat(content.body()).isEqualTo(listOf(png, jpeg)[index])
            assertThat(content.headers().firstValue("Content-Type")).hasValue(photo["contentType"].toString())
            assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store")
            assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        }
        val appended = multipart(actorId, "$ITEMS/$id/photos", listOf(png))
        assertThat(appended.statusCode()).isEqualTo(201)
        assertThat(rows(appended)).hasSize(1)
        val deletedPath = "$ITEMS/$id/photos/${initial.first()["id"]}"
        assertThat(request("DELETE", deletedPath, actorId).statusCode()).isEqualTo(204)
        assertThat(request("GET", "$deletedPath/content", actorId).statusCode()).isEqualTo(404)
        assertThat(request("DELETE", deletedPath, actorId).statusCode()).isEqualTo(404)
        assertThat(objectBody(request("GET", "$ITEMS/$id", actorId))).isEqualTo(item)
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", id)).isZero()
        assertThat(request("DELETE", "$ITEMS/$id?version=1", actorId).statusCode()).isEqualTo(204)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", id)).isZero()
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", id)).isEqualTo(1)
        assertThat(objects).hasSize(3)
        assertThat(request("GET", "$ITEMS/$id/photos/${initial.last()["id"]}/content", actorId).statusCode()).isEqualTo(404)
        assertThat(multipart(actorId, item = ITEM).statusCode()).isEqualTo(201)
        assertThat(request("POST", ITEMS, actorId, ITEM).statusCode()).isEqualTo(201)
    }

    @Test
    fun `every photo route requires an active actor and hides foreign or absent IDs`() {
        val owner = user()
        val actorId = actorId(owner)
        val id = create(actorId)
        val photoId = number(rows(multipart(actorId, "$ITEMS/$id/photos", listOf(png))).single(), "id")
        val stranger = actorId(user())
        for (target in listOf(id, Long.MAX_VALUE)) {
            val path = "$ITEMS/$target/photos"
            assertThat(multipart(stranger, path, listOf(png)).statusCode()).isEqualTo(404)
            assertThat(request("GET", path, stranger).statusCode()).isEqualTo(404)
            assertThat(request("GET", "$path/$photoId/content", stranger).statusCode()).isEqualTo(404)
            assertThat(request("DELETE", "$path/$photoId", stranger).statusCode()).isEqualTo(404)
        }
        val second = create(actorId)
        for (target in listOf(second, id)) {
            val missingPhoto = if (target == second) photoId else Long.MAX_VALUE
            assertThat(request("GET", "$ITEMS/$target/photos/$missingPhoto/content", actorId).statusCode()).isEqualTo(404)
            assertThat(request("DELETE", "$ITEMS/$target/photos/$missingPhoto", actorId).statusCode()).isEqualTo(404)
        }
        users.changeRoleAndStatus(owner.id!!, owner.version, owner.role, UserStatus.BLOCKED)
        for ((deniedActorId, status) in listOf(null to 400, "invalid" to 400, actorId to 403)) {
            val path = "$ITEMS/$id/photos"
            assertThat(multipart(deniedActorId, path, listOf(png)).statusCode()).isEqualTo(status)
            assertThat(multipart(deniedActorId, files = listOf(png), item = ITEM).statusCode()).isEqualTo(status)
            assertThat(request("GET", path, deniedActorId).statusCode()).isEqualTo(status)
            assertThat(request("GET", "$path/$photoId/content", deniedActorId).statusCode()).isEqualTo(status)
            assertThat(request("DELETE", "$path/$photoId", deniedActorId).statusCode()).isEqualTo(status)
        }
        assertThat(objects).hasSize(1)
    }

    @Test
    fun `all files are validated before storage and size and count boundaries are enforced`() {
        val actorId = actorId(user())
        val id = create(actorId)
        val path = "$ITEMS/$id/photos"
        for ((bytes, status) in listOf(byteArrayOf() to 400, "broken".toByteArray() to 400,
            png.copyOf(12) to 400, image("gif") to 415, png.copyOf(10_000_001) to 413)) {
            assertThat(multipart(actorId, path, listOf(png, bytes)).statusCode()).isEqualTo(status)
        }
        assertThat(multipart(actorId, path, List(6) { png }).statusCode()).isEqualTo(400)
        assertThat(multipart(actorId, path).statusCode()).isEqualTo(400)
        assertThat(multipart(actorId, path, List(6) { png.copyOf(9_000_000) }).statusCode()).isEqualTo(413)
        assertThat(multipart(actorId, files = listOf(png), item = "{}").statusCode()).isEqualTo(400)
        verifyNoInteractions(storage)
        assertThat(multipart(actorId, path, listOf(png.copyOf(10_000_000))).statusCode()).isEqualTo(201)
        assertThat(multipart(actorId, path, List(4) { jpeg }).statusCode()).isEqualTo(201)
        assertThat(rows(request("GET", path, actorId))).hasSize(5)
        assertThat(multipart(actorId, path, listOf(png)).statusCode()).isEqualTo(409)
        assertThat(rows(request("GET", path, actorId))).hasSize(5)
    }

    @Test
    fun `multipart creation with unknown category avoids storage and database writes`() {
        val owner = user()
        val actorId = actorId(owner)
        val item = """{"name":"Shirt","categoryId":${Long.MAX_VALUE},"color":"White","material":"Cotton"}"""
        assertThat(multipart(actorId, files = listOf(png), item = item).statusCode()).isEqualTo(400)
        verifyNoInteractions(storage)
        assertThat(count("wardrobe_item", "owner_id", owner.id!!)).isZero()
    }

    @Test
    fun `full item rejects repeated batches without storage interactions`() {
        val actorId = actorId(user())
        val id = create(actorId)
        val path = "$ITEMS/$id/photos"
        assertThat(multipart(actorId, path, List(5) { png }).statusCode()).isEqualTo(201)
        clearInvocations(storage)
        repeat(2) {
            assertThat(multipart(actorId, path, List(5) { png }).statusCode()).isEqualTo(409)
        }
        verifyNoInteractions(storage)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", id)).isEqualTo(5)
        assertThat(objects).hasSize(5)
    }

    @Test
    fun `batch exceeding remaining capacity avoids storage while exact fit succeeds`() {
        val actorId = actorId(user())
        val id = create(actorId)
        val path = "$ITEMS/$id/photos"
        assertThat(multipart(actorId, path, List(3) { png }).statusCode()).isEqualTo(201)
        clearInvocations(storage)
        assertThat(multipart(actorId, path, List(3) { png }).statusCode()).isEqualTo(409)
        verifyNoInteractions(storage)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", id)).isEqualTo(3)
        assertThat(objects).hasSize(3)
        assertThat(multipart(actorId, path, List(2) { png }).statusCode()).isEqualTo(201)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", id)).isEqualTo(5)
        assertThat(objects).hasSize(5)
    }

    @Test
    fun `storage failure leaves no partial item or metadata and download failure is sanitized`() {
        val owner = user()
        val actorId = actorId(owner)
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            if (objects.isNotEmpty()) throw PhotoStorageUnavailableException()
            objects[call.getArgument(0)] = call.getArgument(2)
            null
        }.`when`(storage).put(anyString(), anyString(), anyArgument(ByteArray::class.java) ?: byteArrayOf())
        val failed = multipart(actorId, files = listOf(png, jpeg), item = ITEM)
        assertThat(failed.statusCode()).isEqualTo(503)
        assertThat(failed.body()).isEmpty()
        assertThat(count("wardrobe_item", "owner_id", owner.id!!)).isZero()
        val id = create(actorId)
        assertThat(multipart(actorId, "$ITEMS/$id/photos", listOf(png)).statusCode()).isEqualTo(503)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", id)).isZero()
        storageOutsideTransactions()
        val photoId = number(rows(multipart(actorId, "$ITEMS/$id/photos", listOf(png))).single(), "id")
        doAnswer { throw PhotoStorageUnavailableException() }.`when`(storage).get(anyString(), anyLong())
        val download = request("GET", "$ITEMS/$id/photos/$photoId/content", actorId)
        assertThat(download.statusCode()).isEqualTo(503)
        assertThat(download.body()).isEmpty()
    }

    @Test
    fun `database failure rolls back newly created item and earlier metadata after uploads`() {
        val owner = user()
        val actorId = actorId(owner)
        val existing = create(actorId)
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            val key = call.getArgument<String>(0)
            objects[key] = call.getArgument(2)
            if (objects.size == 2) jdbc.update("""
                INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
                VALUES (?, ?, 'image/png', ?, now())
            """.trimIndent(), existing, key, png.size)
            null
        }.`when`(storage).put(anyString(), anyString(), anyArgument(ByteArray::class.java) ?: byteArrayOf())
        assertThat(multipart(actorId, files = listOf(png, png), item = ITEM).statusCode()).isEqualTo(409)
        assertThat(count("wardrobe_item", "owner_id", owner.id!!)).isEqualTo(1)
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM wardrobe_item_photo p JOIN wardrobe_item i ON i.id = p.wardrobe_item_id
            WHERE i.owner_id = ?
        """.trimIndent(), Long::class.java, owner.id)).isEqualTo(1)
        assertThat(objects).hasSize(2)
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", existing)).isZero()
    }

    @Test
    fun `concurrent uploads serialize count checks and cannot exceed five`() {
        val actorId = actorId(user())
        val id = create(actorId)
        val path = "$ITEMS/$id/photos"
        assertThat(multipart(actorId, path, List(4) { png }).statusCode()).isEqualTo(201)
        val uploaded = CyclicBarrier(2)
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            objects[call.getArgument(0)] = call.getArgument(2)
            uploaded.await(10, TimeUnit.SECONDS)
            null
        }.`when`(storage).put(anyString(), anyString(), anyArgument(ByteArray::class.java) ?: byteArrayOf())
        Executors.newFixedThreadPool(2).use { executor ->
            val attempts = List(2) { executor.submit(Callable { multipart(actorId, path, listOf(png)).statusCode() }) }
            assertThat(attempts.map { it.get(20, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(201, 409)
        }
        assertThat(rows(request("GET", path, actorId))).hasSize(5)
        assertThat(objects).hasSize(6)
    }

    @Test
    fun `item deletion while upload is paused prevents metadata creation`() {
        val actorId = actorId(user())
        val id = create(actorId)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            objects[call.getArgument(0)] = call.getArgument(2)
            entered.countDown()
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
            null
        }.`when`(storage).put(anyString(), anyString(), anyArgument(ByteArray::class.java) ?: byteArrayOf())
        Executors.newSingleThreadExecutor().use { executor ->
            val upload = executor.submit(Callable { multipart(actorId, "$ITEMS/$id/photos", listOf(png)) })
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
                assertThat(request("DELETE", "$ITEMS/$id?version=1", actorId).statusCode()).isEqualTo(204)
            } finally { release.countDown() }
            assertThat(upload.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(404)
        }
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", id)).isZero()
        assertThat(objects).hasSize(1)
    }

    @Test
    fun `photo or item deletion during download revokes the in flight response`() {
        val actorId = actorId(user())
        for (deleteItem in listOf(false, true)) {
            val id = create(actorId)
            val photoId = number(rows(multipart(actorId, "$ITEMS/$id/photos", listOf(png))).single(), "id")
            val path = "$ITEMS/$id/photos/$photoId"
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            doAnswer { call ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                entered.countDown()
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
                objects.getValue(call.getArgument(0))
            }.`when`(storage).get(anyString(), anyLong())
            Executors.newSingleThreadExecutor().use { executor ->
                val download = executor.submit(Callable { request("GET", "$path/content", actorId) })
                try {
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
                    assertThat(request("DELETE", if (deleteItem) "$ITEMS/$id?version=1" else path, actorId).statusCode())
                        .isEqualTo(204)
                } finally { release.countDown() }
                val response = download.get(20, TimeUnit.SECONDS)
                assertThat(response.statusCode()).isEqualTo(404)
                assertThat(response.body()).isEmpty()
            }
        }
    }

    private fun user(role: UserRole = UserRole.USER): AppUser =
        jdbc.insertUser(UUID.randomUUID().toString(), "!", role)

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun create(actorId: String): Long = number(objectBody(request("POST", ITEMS, actorId, ITEM)), "id")

    private fun request(method: String, path: String, actorId: String? = null, body: String? = null): HttpResponse<ByteArray> =
        send(method, path, actorId, "application/json", body?.toByteArray() ?: byteArrayOf())

    private fun multipart(actorId: String?, path: String = ITEMS, files: List<ByteArray> = emptyList(), item: String? = null): HttpResponse<ByteArray> {
        val boundary = "test-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream()
        fun part(name: String, bytes: ByteArray, filename: String = "", type: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"$filename\r\nContent-Type: $type\r\n\r\n".toByteArray())
            body.write(bytes)
            body.write("\r\n".toByteArray())
        }
        if (item != null) part("item", item.toByteArray(), type = "application/json")
        files.forEach { part("photos", it, "; filename=\"misleading.txt\"", "text/plain") }
        body.write("--$boundary--\r\n".toByteArray())
        return send("POST", path, actorId, "multipart/form-data; boundary=$boundary", body.toByteArray())
    }

    private fun send(method: String, path: String, actorId: String?, type: String, bytes: ByteArray): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", type).method(method, HttpRequest.BodyPublishers.ofByteArray(bytes))
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun objectBody(response: HttpResponse<ByteArray>): Map<String, Any> = JsonPath.read(String(response.body()), "$")
    private fun rows(response: HttpResponse<ByteArray>): List<Map<String, Any>> = JsonPath.read(String(response.body()), "$")
    private fun number(row: Map<String, Any>, key: String): Long = (row.getValue(key) as Number).toLong()
    private fun count(table: String, column: String, id: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Long::class.java, id)!!
    private fun image(format: String): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), format, it)
    }.toByteArray()

    companion object {
        private const val ITEMS = "/api/wardrobe/items"
        private const val ITEM = """{"name":"Shirt","categoryId":1,"color":"White","material":"Cotton"}"""
    }
}
