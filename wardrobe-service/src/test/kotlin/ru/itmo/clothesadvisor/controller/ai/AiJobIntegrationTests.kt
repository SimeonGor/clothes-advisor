package ru.itmo.clothesadvisor.controller.ai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import ru.itmo.clothesadvisor.client.file.FileClient
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertOutfit
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiJobIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: JsonMapper
    @MockitoBean private lateinit var files: FileClient
    @MockitoBean private lateinit var provider: RecommendationClient
    private val http = HttpClient.newHttpClient()
    private var owner = 0L
    private var shirt = 0L
    private val outfits = "/api/v2/outfits/ai"
    private val capsules = "/api/v2/me/capsules/ai"
    private val jobs = "/api/v2/me/ai-jobs"
    private val body = """{"name":"Daily","weather":{"temperatureC":14.5,"windSpeedMps":0,"precipitationTypeId":1}}"""

    @BeforeEach
    fun wardrobe() {
        owner = jdbc.insertUser("user", "hash", UserRole.USER).id
        shirt = item("Shirt")
    }

    @AfterEach
    fun noExternalSelection() {
        http.close()
        verifyNoInteractions(files, provider)
    }

    @Test
    fun `acceptance freezes private and model input and replay returns the original queued receipt`() {
        jdbc.update("INSERT INTO style_preferences (owner_id, preferred_colors_and_materials, version) VALUES (?, 'blue', 1)", owner)
        val response = call(outfits, body, "once")
        assertThat(response.statusCode()).isEqualTo(202)
        val jobId = json(response)["jobId"].asString()
        val path = response.headers().firstValue("Location").orElseThrow()
        assertThat(path).isEqualTo("$jobs/$jobId")
        val payload = mapper.readTree(value("SELECT payload::text FROM outbox_event"))
        val snapshot = mapper.readTree(value("SELECT input::text FROM ai_job"))
        assertThat(snapshot["name"].asString()).isEqualTo("Daily")
        assertThat(snapshot["selection"]).isEqualTo(payload["input"])
        assertThat(snapshot["precipitationTypeId"].asLong()).isEqualTo(1)
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
            "requestId", "ownerId", "requestMonth", "goal", "acceptedAt", "startDeadline", "inputVersion", "input",
        )
        assertThat(payload["requestId"].asString()).isEqualTo(jobId)
        assertThat(payload["requestMonth"].asString()).isEqualTo(value("SELECT request_month::text FROM ai_job"))
        assertThat(payload["ownerId"].asLong()).isEqualTo(owner)
        assertThat(payload["goal"].asString()).isEqualTo("OUTFIT")
        assertThat(payload["inputVersion"].asInt()).isEqualTo(1)
        val acceptedAt = Instant.parse(payload["acceptedAt"].asString())
        assertThat(Duration.between(acceptedAt, Instant.parse(payload["startDeadline"].asString()))).isEqualTo(Duration.ofMinutes(15))
        assertThat(jdbc.queryForObject("SELECT created_at FROM outbox_event", java.sql.Timestamp::class.java)!!.toInstant()).isEqualTo(acceptedAt)
        assertThat(payload["input"]["stylePreferences"]["preferredColorsAndMaterials"].asString()).isEqualTo("blue")
        val candidate = payload["input"]["candidates"][0]
        assertThat(candidate.propertyNames()).containsExactlyInAnyOrder(
            "itemId", "name", "categoryCode", "categoryName", "color", "material", "photoId", "fileId", "contentType", "sizeBytes",
        )
        assertThat(candidate["fileId"].asString()).isEqualTo(value("SELECT file_id::text FROM wardrobe_item_photo"))
        jdbc.update("UPDATE wardrobe_item SET name = 'Changed' WHERE id = ?", shirt)
        jdbc.update("UPDATE ai_job SET status = 'SUCCEEDED', result_type = 'OUTFIT', result_id = 99")

        val replay = call(outfits, body, "once")
        assertThat(replay.statusCode()).isEqualTo(202)
        assertThat(replay.body()).isEqualTo(response.body())
        assertThat(replay.headers().firstValue("Location")).hasValue(path)
        val status = json(call(path))
        assertThat(status["status"].asString()).isEqualTo("SUCCEEDED")
        assertThat(status["result"]["url"].asString()).isEqualTo("/api/outfits/99")
        assertThat(status["error"].isNull).isTrue()
        assertThat(count("outbox_event")).isEqualTo(1)
        assertThat(value("SELECT payload->'input'->'candidates'->0->>'name' FROM outbox_event")).isEqualTo("Shirt")
    }

    @Test
    fun `replacement preserves required members and weather and capsule uses its own goal`() {
        val removed = item("Coat")
        val extra = item("Scarf")
        val source = jdbc.insertOutfit(owner, listOf(shirt, removed))
        jdbc.update("UPDATE outfit SET occasion = 'Dinner', preferred_style = 'Formal' WHERE id = ?", source)
        val path = "/api/v2/outfits/$source/ai-replacements"
        val response = call(path, """{"name":"New","replaceItemIds":[$removed],"candidateItemIds":[$extra,$removed]}""")
        assertThat(response.statusCode()).isEqualTo(202)
        val input = mapper.readTree(value("SELECT input::text FROM ai_job"))
        assertThat(input["sourceOutfitId"].asLong()).isEqualTo(source)
        assertThat(input["selection"]["requiredItemIds"].values().map { it.asLong() }).containsExactly(shirt)
        assertThat(input["selection"]["candidates"].values().map { it["itemId"].asLong() }).containsExactlyInAnyOrder(shirt, extra)
        assertThat(input["selection"]["occasion"].asString()).isEqualTo("Dinner")
        assertThat(input["selection"]["preferredStyle"].asString()).isEqualTo("Formal")
        assertError(call(path, """{"name":"New","replaceItemIds":[$extra]}"""), 400, "invalid_request")
        assertThat(call(capsules, body).statusCode()).isEqualTo(202)
        assertThat(value("SELECT payload->>'goal' FROM outbox_event WHERE payload->>'goal' = 'CAPSULE'")).isEqualTo("CAPSULE")
        assertThat(call(capsules, body).statusCode()).isEqualTo(202)
        assertThat(count("ai_job")).isEqualTo(3)
        assertThat(count("idempotency_command")).isZero()
    }

    @Test
    fun `key checks precede version checks and deserialization and old failed and in-progress states survive`() {
        assertThat(call(outfits, body, "key").statusCode()).isEqualTo(202)
        val legacy = "/api/outfits/ai"
        assertError(call(legacy, body, "key"), 409, "idempotency_api_version_conflict")
        assertError(call(legacy, body.replace("Daily", "Changed"), "key"), 409, "idempotency_key_reused")
        jdbc.update("UPDATE idempotency_command SET response_status = 201, response_json = '{\"id\":1}'")
        assertError(call(outfits, body, "key"), 409, "idempotency_api_version_conflict")
        jdbc.update("UPDATE idempotency_command SET state = 'RUNNING', response_status = null, response_json = null")
        assertError(call(outfits, body, "key"), 409, "idempotency_in_progress")
        jdbc.update("UPDATE idempotency_command SET state = 'FAILED'")
        assertError(call(outfits, body, "key"), 409, "idempotency_failed")
        assertThat(count("ai_job")).isEqualTo(1)
    }

    @Test
    fun `concurrent requests cannot overfill the shared queue and replay needs no spare capacity`() {
        val original = call(outfits, body, "first")
        repeat(3) { assertThat(call(outfits, body).statusCode()).isEqualTo(202) }
        jdbc.update("UPDATE ai_job SET status = 'RUNNING'")
        Executors.newFixedThreadPool(4).use { pool ->
            val start = CountDownLatch(1)
            val futures = (1..4).map {
                pool.submit<HttpResponse<String>> { start.await(); call(outfits, body) }
            }
            start.countDown()
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(responses.count { it.statusCode() == 202 }).isEqualTo(1)
            responses.filter { it.statusCode() != 202 }.forEach { assertError(it, 503, "ai_queue_full") }
        }
        assertError(call(outfits, body, "over"), 503, "ai_queue_full")
        assertThat(call(outfits, body, "first").body()).isEqualTo(original.body())
        assertThat(count("ai_job")).isEqualTo(5)
        assertThat(count("outbox_event")).isEqualTo(5)
        jdbc.update("UPDATE ai_job SET status = 'FAILED', error_code = 'queue_expired' WHERE job_id = ?::uuid", json(original)["jobId"].asString())
        assertError(call(outfits, body, "over"), 409, "idempotency_failed")
        assertThat(call(outfits, body, "new").statusCode()).isEqualTo(202)
    }

    @Test
    fun `concurrent requests sharing a key create one immutable admission`() {
        Executors.newFixedThreadPool(4).use { pool ->
            val start = CountDownLatch(1)
            val futures = (1..4).map {
                pool.submit<HttpResponse<String>> {
                    start.await()
                    call(outfits, body, "concurrent")
                }
            }
            start.countDown()
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }
            val accepted = responses.filter { it.statusCode() == 202 }
            assertThat(accepted).isNotEmpty()
            assertThat(accepted.map { it.body() }.distinct()).hasSize(1)
            responses.filter { it.statusCode() != 202 }.forEach { assertError(it, 409, "idempotency_in_progress") }
            assertThat(call(outfits, body, "concurrent").body()).isEqualTo(accepted.first().body())
        }
        assertThat(count("ai_job")).isEqualTo(1)
        assertThat(count("outbox_event")).isEqualTo(1)
        assertThat(count("idempotency_command")).isEqualTo(1)
    }

    @Test
    fun `outbox failure rolls back unkeyed acceptance`() {
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT reject_request_test CHECK (type <> 'RecommendationRequested')")
        try {
            assertError(call(outfits, body), 503, "ai_unavailable")
            assertThat(count("ai_job")).isZero()
            assertThat(count("outbox_event")).isZero()
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT reject_request_test")
        }
    }

    @Test
    fun `failure writing the 202 rolls back job and outbox and leaves the claimed key failed`() {
        jdbc.execute("ALTER TABLE idempotency_command ADD CONSTRAINT reject_accepted_test CHECK (response_status <> 202)")
        try {
            assertError(call(outfits, body, "key"), 503, "ai_unavailable")
            assertThat(count("ai_job")).isZero()
            assertThat(count("outbox_event")).isZero()
            assertThat(value("SELECT state FROM idempotency_command")).isEqualTo("FAILED")
        } finally {
            jdbc.execute("ALTER TABLE idempotency_command DROP CONSTRAINT reject_accepted_test")
        }
    }

    @Test
    fun `read access, pagination, terminal errors and account outages use the public contract`() {
        val first = call(outfits, body)
        val second = call(capsules, body)
        val firstId = json(first)["jobId"].asString()
        val firstPath = "$jobs/$firstId"
        jdbc.update("UPDATE ai_job SET status='UNKNOWN', error_code='ai_outcome_unknown' WHERE job_id=?::uuid", firstId)
        val page = call("$jobs?page=0&size=1")
        assertThat(page.headers().firstValue("X-Total-Count")).hasValue("2")
        assertThat(json(page)[0]["jobId"]).isEqualTo(json(second)["jobId"])
        val status = json(call(firstPath))
        assertThat(status["error"]["code"].asString()).isEqualTo("ai_outcome_unknown")
        assertThat(status["result"].isNull).isTrue()
        assertError(call("$jobs?size=51"), 400, "invalid_request")
        assertError(call("$jobs?page=-1"), 400, "invalid_request")
        val other = jdbc.insertUser("other", "hash", UserRole.USER).id
        val stylist = jdbc.insertUser("stylist", "hash", UserRole.STYLIST).id
        assertError(call(firstPath, actor = other), 404, "not_found")
        assertThat(json(call(jobs, actor = other)).size()).isZero()
        assertError(call(firstPath, actor = stylist), 403, "forbidden")
        assertError(call(firstPath, actor = null), 401, "unauthorized")
        assertError(call(outfits, body, actor = other), 503, "ai_unavailable")
        assertError(call(outfits, body, actor = stylist), 403, "forbidden")
        jdbc.update("UPDATE app_user SET status = 'BLOCKED' WHERE id = ?", owner)
        assertError(call(firstPath), 403, "forbidden")
        doThrow(AccountUnavailableException()).`when`(accounts).getUser(owner)
        assertError(call(outfits, body), 503, "ai_unavailable")
    }

    @Test
    fun `foreign and duplicate items, missing or invalid photos and invalid form reject acceptance`() {
        val other = jdbc.insertUser("other", "hash", UserRole.USER).id
        val foreignItem = jdbc.insertItem(other, CreateWardrobeItemRequest("Coat", 1, "Red", "Cotton")).id
        fun withIds(ids: String) = body.dropLast(1) + ""","candidateItemIds":[$ids]}"""
        assertError(call(outfits, withIds("$shirt,$shirt")), 400, "invalid_request")
        assertError(call(outfits, withIds("$foreignItem")), 404, "not_found")
        assertError(call(outfits, body.replace("Daily", "")), 400, "invalid_request")
        jdbc.update("UPDATE wardrobe_item_photo SET content_type = 'text/plain'")
        assertError(call(outfits, body), 400, "invalid_request")
        jdbc.update("DELETE FROM wardrobe_item_photo")
        assertError(call(outfits, body), 400, "invalid_request")
        assertThat(count("ai_job")).isZero()
        assertThat(count("outbox_event")).isZero()
    }

    private fun item(name: String): Long {
        val id = jdbc.insertItem(owner, CreateWardrobeItemRequest(name, 1, "Blue", "Cotton")).id
        jdbc.update("INSERT INTO wardrobe_item_photo (wardrobe_item_id, file_id, content_type, size_bytes) VALUES (?, ?, 'image/png', 32)", id, UUID.randomUUID())
        return id
    }

    private fun call(path: String, body: String? = null, key: String? = null, actor: Long? = owner): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (actor != null) request.header("Authorization", bearer(actor.toString()))
        if (key != null) request.header("Idempotency-Key", key)
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>) = mapper.readTree(response.body())
    private fun value(query: String) = jdbc.queryForObject(query, String::class.java)!!
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun assertError(response: HttpResponse<String>, status: Int, code: String) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status)
        assertThat(json(response)["error"].asString()).isEqualTo(code)
    }
}
