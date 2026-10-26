package ru.itmo.clothesadvisor.controller.ai

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionResponse
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.model.capsule.CapsuleItem
import ru.itmo.clothesadvisor.model.capsule.CapsuleWeather
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.capsule.CapsuleItemRepository
import ru.itmo.clothesadvisor.repository.capsule.CapsuleWeatherRepository
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage
import java.math.BigDecimal

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class AiCapsuleIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var storage: S3PhotoStorage
    @MockitoBean private lateinit var provider: RecommendationClient
    @MockitoSpyBean private lateinit var weather: CapsuleWeatherRepository
    @MockitoSpyBean private lateinit var composition: CapsuleItemRepository
    @MockitoSpyBean private lateinit var commands: IdempotencyCommandRepository
    private val client = HttpClient.newHttpClient()
    private val path = "/api/me/capsules"
    private var downloads = 0
    private var providerCalls = 0
    private var onDownload: () -> Unit = {}
    private var select: (ItemSelectionRequest) -> ItemSelectionResponse = {
        ItemSelectionResponse(it.candidates.map { item -> item.itemId }.reversed(), "Подходит для нескольких сочетаний.")
    }

    @BeforeEach
    fun externalCalls() {
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(Schedulers.isInNonBlockingThread()).isFalse()
            downloads++
            onDownload()
            byteArrayOf(1, 2, 3)
        }.`when`(storage).get(anyString(), anyLong())
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(Schedulers.isInNonBlockingThread()).isFalse()
            providerCalls++
            select(it.getArgument(0))
        }.`when`(provider).selectCapsule(any(ItemSelectionRequest::class.java) ?: selection())
    }

    @AfterEach fun close() = client.close()

    @ParameterizedTest
    @ValueSource(strings = ["absent", "null", "explicit"])
    fun `AI capsule persists exact weather context preferences order and database date`(mode: String) {
        // given
        val owner = user()
        val first = item(owner)
        val second = item(owner)
        val noPhoto = item(owner, false)
        jdbc.update("""INSERT INTO style_preferences (owner_id, preferred_colors_and_materials, version)
            VALUES (?, 'Blue cotton', 1)""", owner)
        val explanation = "  Warm layers.\n".padEnd(2000, 'x')
        select = {
            assertThat(it.candidates.map { candidate -> candidate.itemId }).containsExactly(first, second)
            assertThat(it.requiredItemIds).isNull()
            assertThat(it.occasion).isEqualTo("Work")
            assertThat(it.preferredStyle).isEqualTo("Classic")
            assertThat(it.stylePreferences!!.preferredColorsAndMaterials).isEqualTo("Blue cotton")
            ItemSelectionResponse(listOf(second, first), explanation)
        }
        val candidates = when (mode) { "explicit" -> "[$second,$noPhoto,$first]"; "null" -> "null"; else -> null }

        // when
        val created = http("POST", owner, body(candidates))
        val id = id(created)
        val detail = http("GET", owner, target = "$path/$id")
        val listed = http("GET", owner, target = "$path?page=0&size=1")

        // then
        assertThat(created.headers().firstValue("Location")).hasValue("$path/$id")
        val row = JsonPath.parse(created.body())
        assertThat(row.read<String>("$.source")).isEqualTo("AI")
        assertThat(row.read<List<Number>>("$.itemIds").map { it.toLong() }).containsExactly(second, first)
        assertThat(row.read<String>("$.occasion")).isEqualTo("Work")
        assertThat(row.read<String>("$.preferredStyle")).isEqualTo("Classic")
        assertThat(row.read<String>("$.explanation")).isEqualTo(explanation)
        assertThat(row.read<String>("$.createdAt")).isEqualTo(jdbc.queryForObject(
            "SELECT created_at FROM capsule WHERE id = ?", Timestamp::class.java, id)!!.toInstant().toString())
        assertThat(detail.statusCode()).isEqualTo(200)
        assertThat(detail.body()).isEqualTo(created.body())
        assertThat(detail.body()).contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")
        assertThat(listed.statusCode()).isEqualTo(200)
        assertThat(listed.headers().firstValue("X-Total-Count")).hasValue("1")
        assertThat(JsonPath.read<List<Map<String, Any?>>>(listed.body(), "$")).containsExactly(JsonPath.read(created.body(), "$"))
        assertThat(jdbc.queryForObject("SELECT temperature_c FROM capsule_weather WHERE capsule_id = ?", BigDecimal::class.java, id))
            .isEqualByComparingTo(TEMPERATURE)
        assertThat(downloads).isEqualTo(2)
        assertThat(providerCalls).isEqualTo(1)
    }

    @Test
    fun `key replay survives deletion while capsule deletion releases items and weather`() {
        // given
        val owner = user()
        val item = item(owner)
        val created = http("POST", owner, body(), key = "saved")
        val id = id(created)

        // when
        val referenced = http("DELETE", owner, target = "/api/wardrobe/items/$item?version=1")
        val deleted = http("DELETE", owner, target = "$path/$id")
        val replay = http("POST", owner, body(), key = "saved")
        val itemDeleted = http("DELETE", owner, target = "/api/wardrobe/items/$item?version=1")

        // then
        assertEmpty(referenced, 409)
        assertThat(deleted.statusCode()).isEqualTo(204)
        assertNoCapsule()
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.body()).isEqualTo(created.body())
        assertThat(replay.headers().firstValue("Location")).hasValue("$path/$id")
        assertThat(itemDeleted.statusCode()).isEqualTo(204)
        assertThat(providerCalls).isEqualTo(1)
        assertThat(downloads).isEqualTo(1)

        // when
        jdbc.update("UPDATE app_user SET login = 'disconnected' WHERE id = ?", owner)
        val inaccessibleReplay = http("POST", owner, body(), key = "saved")

        // then
        assertEmpty(inaccessibleReplay, 503)
        assertThat(providerCalls).isEqualTo(1)
    }

    @Test
    fun `old manual replay omits new fields while fresh detail returns USER`() {
        // given
        val owner = user()
        val item = item(owner, false)
        val payload = """{"name":"Manual","itemIds":[$item]}"""
        val created = http("POST", owner, payload, path, "old")
        val id = id(created)
        jdbc.update("""UPDATE idempotency_command SET response_json = (response_json::jsonb - 'source')::text
            WHERE actor_id = ? AND operation = 'CREATE_CAPSULE' AND command_key = 'old'""", owner)

        // when
        val replay = http("POST", owner, payload, path, "old")
        val detail = http("GET", owner, target = "$path/$id")

        // then
        assertThat(replay.statusCode()).isEqualTo(201)
        val old = JsonPath.read<Map<String, Any?>>(replay.body(), "$")
        assertThat(old.keys).containsExactlyInAnyOrder("id", "ownerId", "name", "itemIds", "createdAt")
        assertThat(JsonPath.read<Map<String, Any?>>(detail.body(), "$")).containsAllEntriesOf(old).containsEntry("source", "USER")
        verifyNoInteractions(provider, storage)
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["  ", "oversized"])
    fun `invalid explanation fails before capsule insert and failed key cannot retry`(explanation: String?) {
        // given
        val owner = user()
        val item = item(owner)
        select = { ItemSelectionResponse(listOf(item), if (explanation == "oversized") "x".repeat(2001) else explanation) }

        // when
        val failed = http("POST", owner, body(), key = "bad-explanation")
        val replay = http("POST", owner, body(), key = "bad-explanation")

        // then
        assertEmpty(failed, 502)
        assertThat(replay.statusCode()).isEqualTo(409)
        assertThat(replay.body()).contains("idempotency_failed")
        assertNoCapsule()
        assertThat(jdbc.queryForObject("SELECT is_called FROM capsule_id_seq", Boolean::class.java)).isFalse()
        assertThat(providerCalls).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["STYLIST", "ADMIN", "disconnected"])
    fun `roles and personal connection reject before S3`(actor: String) {
        // given
        val owner = if (actor == "disconnected") user("other") else user(role = UserRole.valueOf(actor))
        item(owner)

        // when
        val response = http("POST", owner, body())

        // then
        assertThat(response.statusCode()).isEqualTo(if (actor == "disconnected") 503 else 403)
        assertNoCapsule()
        verifyNoInteractions(provider, storage)
    }

    @Test
    fun `invalid inputs and foreign or missing explicit candidates fail before S3`() {
        // given
        val owner = user()
        val foreign = item(user("other"))
        item(owner)
        val invalid = listOf(body("[]"), body("[null]"), body("[0]"), body("[1,1]"),
            body().replace("Daily", " "), body().replace("Work", "x".repeat(301)),
            body().replace("\"precipitationTypeId\":1", "\"precipitationTypeId\":999"),
            body().replace(WIND, "-1"))

        // when
        val failures = invalid.map { http("POST", owner, it) }
        val foreignResult = http("POST", owner, body("[$foreign]"))
        val missing = http("POST", owner, body("[999999]"))

        // then
        assertThat(failures.map { it.statusCode() }).containsOnly(400)
        assertEmpty(foreignResult, 404)
        assertEmpty(missing, 404)
        assertNoCapsule()
        verifyNoInteractions(provider, storage)
    }

    @ParameterizedTest
    @ValueSource(strings = ["photo", "availability"])
    fun `changes to an unselected candidate fail final snapshot validation`(change: String) {
        // given
        val owner = user()
        val selected = item(owner)
        val unselected = item(owner)
        select = {
            if (change == "photo") jdbc.update("DELETE FROM wardrobe_item_photo WHERE wardrobe_item_id = ?", unselected)
            else jdbc.update("UPDATE wardrobe_item SET availability = 'STORED' WHERE id = ?", unselected)
            ItemSelectionResponse(listOf(selected), "Fits.")
        }

        // when
        val response = http("POST", owner, body())

        // then
        assertEmpty(response, 409)
        assertNoCapsule()
    }

    @ParameterizedTest
    @ValueSource(strings = ["weather", "composition", "key"])
    fun `failure after an insert rolls back capsule weather composition and success key`(stage: String) {
        // given
        val owner = user()
        item(owner)
        if (stage == "weather") doAnswer {
            mockingDetails(weather).mockCreationSettings.defaultAnswer.answer(it)
            throw DataIntegrityViolationException("injected weather failure")
        }.`when`(weather).insert(any(CapsuleWeather::class.java) ?: CapsuleWeather(1, BigDecimal.ZERO, 1, BigDecimal.ZERO))
        if (stage == "composition") doAnswer {
            mockingDetails(composition).mockCreationSettings.defaultAnswer.answer(it)
            throw DataIntegrityViolationException("injected composition failure")
        }.`when`(composition).insertAll(any<Iterable<CapsuleItem>>() ?: emptyList())
        if (stage == "key") doAnswer {
            mockingDetails(commands).mockCreationSettings.defaultAnswer.answer(it)
            throw DataIntegrityViolationException("injected key failure")
        }.`when`(commands).succeed(anyLong(), anyString())

        // when
        val response = http("POST", owner, body(), key = "atomic")

        // then
        assertEmpty(response, 409)
        assertNoCapsule()
        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_command WHERE command_key = 'atomic'", String::class.java))
            .isEqualTo("FAILED")
        assertThat(jdbc.queryForObject("SELECT response_json FROM idempotency_command WHERE command_key = 'atomic'", String::class.java))
            .isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["download", "provider", "save"])
    fun `capsule holds the shared slot through downloads provider and final save`(stage: String) {
        // given
        val owner = user()
        val item = item(owner)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wait = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); Unit }
        if (stage == "download") onDownload = wait
        if (stage == "provider") select = { wait(); ItemSelectionResponse(listOf(item), "Fits.") }
        if (stage == "save") doAnswer {
            wait()
            mockingDetails(composition).mockCreationSettings.defaultAnswer.answer(it)
        }.`when`(composition).insertAll(any<Iterable<CapsuleItem>>() ?: emptyList())
        Executors.newSingleThreadExecutor().use { executor ->
            // when
            val creation = executor.submit(Callable { http("POST", owner, body()) })
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
                val busyCapsule = http("POST", owner, body())
                val busyOutfit = http("POST", owner, body(), "/api/outfits/ai")
                val busyReplacement = http("POST", owner, """{"name":"New","replaceItemIds":[1]}""", "/api/outfits/99/ai-replacements")

                // then
                listOf(busyCapsule, busyOutfit, busyReplacement).forEach { assertEmpty(it, 503) }
                assertThat(downloads).isEqualTo(1)
                assertThat(creation.isDone).isFalse()
                release.countDown()
                assertThat(creation.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201)
            } finally {
                release.countDown()
                creation.cancel(true)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["NO_CAPSULE", "INVALID_RESPONSE", "UNAVAILABLE", "TIMEOUT"])
    fun `provider failures retain public empty statuses without saving`(failure: String) {
        // given
        val owner = user()
        item(owner)
        val reason = AiSelectionFailure.valueOf(failure)
        select = { throw AiSelectionException(reason) }

        // when
        val response = http("POST", owner, body())

        // then
        assertEmpty(response, reason.status)
        assertNoCapsule()
    }

    private fun user(login: String = "user", role: UserRole = UserRole.USER): Long = jdbc.insertUser(login, "!", role).id

    private fun item(owner: Long, photo: Boolean = true): Long {
        val id = jdbc.insertItem(owner, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id
        if (photo) jdbc.update("""INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
            VALUES (?, ?, 'image/png', 3, CURRENT_TIMESTAMP)""", id, "photo-$id")
        return id
    }

    private fun body(candidates: String? = null): String =
        """{"name":"Daily","weather":{"temperatureC":$TEMPERATURE,"precipitationTypeId":1,"windSpeedMps":$WIND},"occasion":"Work","preferredStyle":"Classic"${candidates?.let { ",\"candidateItemIds\":$it" } ?: ""}}"""

    private fun selection() = ItemSelectionRequest(
        ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionWeather(BigDecimal.ZERO, BigDecimal.ZERO, "", ""), emptyList())

    private fun http(method: String, owner: Long, body: String? = null, target: String = "$path/ai", key: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$target")).timeout(Duration.ofSeconds(20))
            .header("X-User-Id", owner.toString())
        if (body != null) request.header("Content-Type", "application/json")
        if (key != null) request.header("Idempotency-Key", key)
        return client.send(request.method(method, body?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun id(response: HttpResponse<String>): Long {
        assertThat(response.statusCode()).isEqualTo(201)
        return JsonPath.read<Number>(response.body(), "$.id").toLong()
    }

    private fun assertNoCapsule() {
        listOf("capsule", "capsule_weather", "capsule_item").forEach {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java)).isZero()
        }
    }

    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    companion object {
        private const val TEMPERATURE = "-123.123456789123456789"
        private const val WIND = "1.123456789123456789"
    }
}
