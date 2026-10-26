package ru.itmo.clothesadvisor.controller

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.insertAccessGrant
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionResponse
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: JsonMapper
    @MockitoBean private lateinit var storage: S3PhotoStorage
    @MockitoBean private lateinit var provider: RecommendationClient
    @MockitoSpyBean private lateinit var commands: IdempotencyCommandRepository
    private val http = HttpClient.newHttpClient()
    private val uploads = AtomicInteger()
    private val selections = AtomicInteger()
    private var onUpload: () -> Unit = {}
    private val png = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()
    private val itemBody = """{"name":"Shirt","categoryId":1,"color":"White","material":"Cotton"}"""
    private val weather = """{"temperatureC":15,"windSpeedMps":0,"precipitationTypeId":1}"""

    @BeforeEach
    fun externalStubs() {
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            uploads.incrementAndGet()
            onUpload()
            null
        }.`when`(storage).put(anyString(), anyString(), any(ByteArray::class.java) ?: png)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            png
        }.`when`(storage).get(anyString(), anyLong())
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            selections.incrementAndGet()
            OutfitSelectionResponse(call.getArgument<OutfitSelectionRequest>(0).candidates.map { it.itemId })
        }.`when`(provider).select(any(OutfitSelectionRequest::class.java) ?: errorPlaceholder())
    }

    @AfterEach fun close() = http.close()

    @ParameterizedTest
    @ValueSource(strings = ["json", "multipart", "photos", "own", "stylist", "ai"])
    fun `all creation paths replay exact original response then recheck current access`(flow: String) {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        val item = jdbc.insertItem(owner, CreateWardrobeItemRequest("Existing", 1, "White", "Cotton")).id
        jdbc.update("INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes) VALUES (?, 'fixture', 'image/png', ?)", item, png.size)
        val actor = if (flow == "stylist") jdbc.insertUser("stylist", "hash", UserRole.STYLIST).id else owner
        if (flow == "stylist") jdbc.insertAccessGrant(owner, actor)
        val path = when (flow) {
            "json", "multipart" -> "/api/wardrobe/items"
            "photos" -> "/api/wardrobe/items/$item/photos"
            "stylist" -> "/api/stylist/clients/$owner/outfits"
            "ai" -> "/api/outfits/ai"
            else -> "/api/outfits"
        }
        val json = when (flow) {
            "json", "multipart" -> itemBody
            "ai" -> """{"name":"Daily","weather":$weather,"candidateItemIds":[$item]}"""
            else -> """{"name":"Daily","weather":$weather,"itemIds":[$item]}"""
        }
        fun send(boundary: String) = if (flow == "multipart" || flow == "photos")
            multipart(actor, path, "same", if (flow == "multipart") json else null, boundary)
            else post(actor, path, "same", json)

        // when
        val first = send("first")
        val counts = listOf(count("wardrobe_item"), count("wardrobe_item_photo"), count("outfit"))
        val replay = send("second")

        // then
        assertThat(first.statusCode()).isEqualTo(201)
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.body()).isEqualTo(first.body())
        assertThat(replay.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"))
        assertThat(listOf(count("wardrobe_item"), count("wardrobe_item_photo"), count("outfit"))).isEqualTo(counts)
        assertThat(count("idempotency_command")).isEqualTo(1)
        assertThat(uploads.get()).isEqualTo(if (flow in listOf("multipart", "photos")) 1 else 0)
        assertThat(selections.get()).isEqualTo(if (flow == "ai") 1 else 0)

        // when
        if (flow == "stylist") jdbc.update("DELETE FROM access_grant")
        else jdbc.update("UPDATE app_user SET status = 'BLOCKED' WHERE id = ?", owner)
        val denied = send("third")

        // then
        assertThat(denied.statusCode()).isEqualTo(if (flow == "stylist") 404 else 403)
    }

    @Test
    fun `legacy AI fingerprints and stored responses replay without context fields or external calls`() {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        val saved = """{"id":80,"ownerId":$owner,"authorId":$owner,"source":"AI","name":"Daily","itemIds":[17],"weather":{"temperatureC":15,"precipitationTypeId":1,"windSpeedMps":0},"createdAt":"2026-01-01T00:00:00Z","likes":0,"dislikes":0}"""
        for (candidateJson in listOf("null", "[17]")) {
            val legacy = """[$owner,{"name":"Daily","weather":{"temperatureC":15,"precipitationTypeId":1,"windSpeedMps":0},"candidateItemIds":$candidateJson}]"""
            val payload = listOf(owner, CreateAiOutfitRequest(
                "Daily", OutfitWeatherDto(BigDecimal("15"), 1, BigDecimal.ZERO),
                if (candidateJson == "null") null else listOf(17),
            ))
            assertThat(mapper.writeValueAsString(payload)).isEqualTo(legacy)
            val bytes = legacy.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            val fingerprint = digest.digest(bytes).joinToString("") { "%02x".format(it) }
            jdbc.update(
                """INSERT INTO idempotency_command
                    (actor_id, operation, command_key, fingerprint, state, response_json, response_status)
                    VALUES (?, 'CREATE_AI_OUTFIT', ?, ?, 'SUCCEEDED', ?, 201)""",
                owner, candidateJson, fingerprint, saved,
            )
            val candidates = if (candidateJson == "null") "" else ",\"candidateItemIds\":$candidateJson"
            val body = """{"name":"Daily","weather":$weather$candidates}"""

            // when
            val replay = post(owner, "/api/outfits/ai", candidateJson, body)
            val explicitNulls = post(owner, "/api/outfits/ai", candidateJson,
                body.dropLast(1) + """, "occasion":null, "preferredStyle":null}""")

            // then
            assertThat(replay.statusCode()).isEqualTo(201)
            assertThat(replay.body()).isEqualTo(saved)
            assertThat(explicitNulls.statusCode()).isEqualTo(201)
            assertThat(explicitNulls.body()).isEqualTo(saved)
            for (field in listOf("occasion", "preferredStyle")) {
                // when
                val conflict = post(owner, "/api/outfits/ai", candidateJson,
                    body.dropLast(1) + """, "$field":"Work"}""")

                // then
                assertThat(conflict.statusCode()).isEqualTo(409)
                assertThat(conflict.body()).contains("idempotency_key_reused")
            }
        }
        assertThat(selections).hasValue(0)
        assertThat(uploads).hasValue(0)
        assertThat(count("outfit")).isZero()
    }

    @Test
    fun `AI replay keeps stored result after upstream selection behavior changes`() {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        val items = listOf("Shirt", "Coat").map { name ->
            jdbc.insertItem(owner, CreateWardrobeItemRequest(name, 1, "White", "Cotton")).id.also { item ->
                jdbc.update("INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes) VALUES (?, ?, 'image/png', ?)", item, "fixture-$item", png.size)
            }
        }
        val body = """{"name":"Daily","weather":$weather,"candidateItemIds":[${items.joinToString(",")}],"occasion":"Work","preferredStyle":"Casual"}"""
        var selected = items.first()
        var explanation = "  Рубашка подходит для работы.\nНа улице тепло.  "
        val originalExplanation = explanation
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            selections.incrementAndGet()
            OutfitSelectionResponse(listOf(selected), explanation)
        }.`when`(provider).select(any(OutfitSelectionRequest::class.java) ?: errorPlaceholder())
        val first = post(owner, "/api/outfits/ai", "same", body)
        val fingerprint = jdbc.queryForObject("SELECT fingerprint FROM idempotency_command", String::class.java)

        // when: the upstream now selects a different item after its prompt changes
        selected = items.last()
        explanation = "Пальто подходит для холода."
        val replay = post(owner, "/api/outfits/ai", "same", body)

        // then
        assertThat(first.statusCode()).isEqualTo(201)
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.body()).isEqualTo(first.body())
        assertThat(mapper.readTree(replay.body()).path("explanation").asString()).isEqualTo(originalExplanation)
        assertThat(jdbc.queryForObject("SELECT explanation FROM outfit", String::class.java)).isEqualTo(originalExplanation)
        assertThat(replay.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"))
        assertThat(selections.get()).isEqualTo(1)
        assertThat(count("outfit")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT fingerprint FROM idempotency_command", String::class.java)).isEqualTo(fingerprint)
        for (changed in listOf(body.replace("Work", "Dinner"), body.replace("Casual", "Formal"))) {
            // when
            val conflict = post(owner, "/api/outfits/ai", "same", changed)

            // then
            assertThat(conflict.statusCode()).isEqualTo(409)
            assertThat(selections).hasValue(1)
        }

        // when
        val fresh = post(owner, "/api/outfits/ai", "new", body)

        // then
        assertThat(fresh.statusCode()).isEqualTo(201)
        assertThat(mapper.readTree(fresh.body()).path("explanation").asString()).isEqualTo(explanation)
        assertThat(selections.get()).isEqualTo(2)
        assertThat(jdbc.queryForList("SELECT wardrobe_item_id FROM outfit_item ORDER BY outfit_id", Long::class.java))
            .containsExactly(items.first(), items.last())
    }

    @Test
    fun `concurrent multipart duplicate returns conflict before first upload completes`() {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        onUpload = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        Executors.newSingleThreadExecutor().use { executor ->
            val first = executor.submit<HttpResponse<String>> { multipart(owner, "/api/wardrobe/items", "key", itemBody) }
            try {
                check(entered.await(10, TimeUnit.SECONDS))

                // when
                val duplicate = multipart(owner, "/api/wardrobe/items", "key", itemBody)

                // then
                assertThat(duplicate.statusCode()).isEqualTo(409)
                assertThat(duplicate.body()).contains("idempotency_in_progress")
                assertThat(count("idempotency_command")).isEqualTo(1)
            } finally {
                release.countDown()
            }
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201)
        }
        assertThat(uploads.get()).isEqualTo(1)
        assertThat(count("wardrobe_item")).isEqualTo(1)
    }

    @Test
    fun `result persistence failure rolls back created resource and records terminal failure`() {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        doThrow(IllegalStateException("snapshot unavailable")).`when`(commands).succeed(anyLong(), anyString())

        // when
        val first = post(owner, "/api/wardrobe/items", "key", itemBody)
        val retry = post(owner, "/api/wardrobe/items", "key", itemBody)

        // then
        assertThat(first.statusCode()).isEqualTo(500)
        assertThat(count("wardrobe_item")).isZero()
        assertThat(retry.statusCode()).isEqualTo(409)
        assertThat(retry.body()).contains("idempotency_failed")
        assertThat(jdbc.queryForObject("SELECT response_json FROM idempotency_command", String::class.java)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["RUNNING", "FAILED"])
    fun `persisted unfinished states never expire or execute again`(state: String) {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        assertThat(post(owner, "/api/wardrobe/items", "key", itemBody).statusCode()).isEqualTo(201)
        jdbc.update("UPDATE idempotency_command SET state = ?, response_json = NULL, response_status = NULL, created_at = '2000-01-01'", state)

        // when
        val retry = post(owner, "/api/wardrobe/items", "key", itemBody)

        // then
        assertThat(retry.statusCode()).isEqualTo(409)
        assertThat(retry.body()).contains(if (state == "RUNNING") "idempotency_in_progress" else "idempotency_failed")
        assertThat(count("wardrobe_item")).isEqualTo(1)
    }

    @Test
    fun `payload mismatch conflicts while a different actor can use the same key`() {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        val other = jdbc.insertUser("other", "hash", UserRole.USER).id
        assertThat(post(owner, "/api/wardrobe/items", "key", itemBody).statusCode()).isEqualTo(201)

        // when
        val mismatch = post(owner, "/api/wardrobe/items", "key", itemBody.replace("Shirt", "Coat"))
        val separate = post(other, "/api/wardrobe/items", "key", itemBody)

        // then
        assertThat(mismatch.statusCode()).isEqualTo(409)
        assertThat(mismatch.body()).contains("idempotency_key_reused")
        assertThat(separate.statusCode()).isEqualTo(201)
        assertThat(count("wardrobe_item")).isEqualTo(2)
    }

    @Test
    fun `upload error is terminal for key and new attempt explicitly repeats external work`() {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        onUpload = { throw PhotoStorageUnavailableException() }

        // when
        val first = multipart(owner, "/api/wardrobe/items", "key", itemBody)
        val retry = multipart(owner, "/api/wardrobe/items", "key", itemBody)
        onUpload = {}
        val newAttempt = multipart(owner, "/api/wardrobe/items", "new", itemBody)

        // then
        assertThat(first.statusCode()).isEqualTo(503)
        assertThat(retry.statusCode()).isEqualTo(409)
        assertThat(retry.body()).contains("idempotency_failed")
        assertThat(newAttempt.statusCode()).isEqualTo(201)
        assertThat(uploads.get()).isEqualTo(2)
        assertThat(count("wardrobe_item")).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `legacy item fingerprints replay response without availability or repeated writes`(multipart: Boolean) {
        // given
        val owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        val id = jdbc.insertItem(owner, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id
        jdbc.update("UPDATE wardrobe_item SET availability = 'STORED' WHERE id = ?", id)
        val saved = """{"id":$id,"name":"Shirt","categoryId":1,"color":"White","material":"Cotton","version":1,"createdAt":"2026-01-01T00:00:00Z","modifiedAt":"2026-01-01T00:00:00Z"}"""
        val legacy = """[$owner,$itemBody]"""
        assertThat(mapper.writeValueAsString(listOf(owner, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton"))))
            .isEqualTo(legacy)
        val digest = MessageDigest.getInstance("SHA-256")
        val parts = listOf(legacy.toByteArray()) + if (multipart) listOf(png) else emptyList()
        parts.forEach { bytes ->
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        jdbc.update(
            """INSERT INTO idempotency_command
                (actor_id, operation, command_key, fingerprint, state, response_json, response_status)
                VALUES (?, 'CREATE_WARDROBE_ITEM', 'legacy', ?, 'SUCCEEDED', ?, 201)""",
            owner, fingerprint, saved,
        )

        // when
        val replay = if (multipart) multipart(owner, "/api/wardrobe/items", "legacy", itemBody)
            else post(owner, "/api/wardrobe/items", "legacy", itemBody)

        // then
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.body()).isEqualTo(saved)
        assertThat(replay.headers().firstValue("Location")).hasValue("/api/wardrobe/items/$id")
        assertThat(count("wardrobe_item")).isEqualTo(1)
        assertThat(count("wardrobe_item_photo")).isZero()
        assertThat(uploads).hasValue(0)
        assertThat(selections).hasValue(0)
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    private fun post(actor: Long, path: String, key: String, body: String) = send(actor, path, key, "application/json", body.toByteArray())

    private fun multipart(actor: Long, path: String, key: String, item: String?, boundary: String = "boundary"): HttpResponse<String> {
        val body = ByteArrayOutputStream()
        if (item != null) body.write("--$boundary\r\nContent-Disposition: form-data; name=\"item\"\r\nContent-Type: application/json\r\n\r\n$item\r\n".toByteArray())
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
        body.write(png)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        return send(actor, path, key, "multipart/form-data; boundary=$boundary", body.toByteArray())
    }

    private fun send(actor: Long, path: String, key: String, contentType: String, body: ByteArray): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("X-User-Id", actor.toString()).header("Idempotency-Key", key)
            .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofString())

    private fun errorPlaceholder() = OutfitSelectionRequest(
        ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "", ""),
        emptyList(),
    )
}
