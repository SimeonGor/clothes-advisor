package ru.itmo.clothesadvisor.controller.stylistrequest

import com.jayway.jsonpath.JsonPath
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertAccessGrant
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertRating
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.outfit.OutfitWeather
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitItemRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitWeatherRepository
import ru.itmo.clothesadvisor.repository.stylistrequest.StylistRequestAnswerRepository
import ru.itmo.clothesadvisor.repository.stylistrequest.StylistRequestRepository

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class StylistRequestAnswerIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean private lateinit var requests: StylistRequestRepository
    @MockitoSpyBean private lateinit var answers: StylistRequestAnswerRepository
    @MockitoSpyBean private lateinit var commands: IdempotencyCommandRepository
    @MockitoSpyBean private lateinit var weather: OutfitWeatherRepository
    @MockitoSpyBean private lateinit var composition: OutfitItemRepository
    private val client = HttpClient.newHttpClient()
    private val ownPath = "/api/me/stylist-requests"

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `answer creates immutable client outfit and exact history with request weather and manual unavailable items`() {
        // given
        val f = fixture()
        val secondItem = item(f.owner)
        jdbc.update("UPDATE wardrobe_item SET availability = 'IN_LAUNDRY' WHERE id = ?", f.item)
        jdbc.update("UPDATE stylist_request SET created_at = '2025-01-01T00:00:00Z', modified_at = '2025-02-01T00:00:00Z' WHERE id = ?", f.id)
        val previous = current(f.id)
        val requestWeather = jdbc.queryForMap("SELECT * FROM stylist_request_weather WHERE request_id = ?", f.id)
        val explanation = "  " + "x".repeat(1996) + "  "
        val body = payload(listOf(secondItem, f.item), explanation = explanation, name = "  " + "x".repeat(296) + "  ")

        // when
        val created = http("POST", f.stylist, body, answerPath(f))
        val own = http("GET", f.owner, path = "$ownPath/${f.id}")
        val recipient = http("GET", f.stylist, path = requestPath(f))

        // then
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(created.headers().firstValue("Location")).hasValue(requestPath(f))
        assertThat(own.body()).isEqualTo(created.body())
        assertThat(recipient.body()).isEqualTo(created.body())
        val response = JsonPath.parse(created.body())
        assertThat(response.read<String>("$.status")).isEqualTo("ANSWERED")
        assertThat(response.read<Number>("$.version").toLong()).isEqualTo(2)
        assertThat(response.read<String>("$.answer.explanation")).isEqualTo(explanation)
        val outfitId = response.read<Number>("$.answer.outfitId").toLong()
        assertThat(Instant.parse(response.read<String>("$.answer.createdAt"))).isEqualTo(
            jdbc.queryForObject("SELECT created_at FROM stylist_request_answer WHERE request_id = ?", Timestamp::class.java, f.id)!!.toInstant(),
        )
        val archived = jdbc.queryForMap("SELECT * FROM stylist_request_history WHERE request_id = ?", f.id)
        assertThat(archived - setOf("request_id", "archived_at")).isEqualTo(previous - "id")
        assertThat(archived["archived_at"]).isEqualTo(current(f.id)["modified_at"])
        assertThat(jdbc.queryForMap("SELECT * FROM stylist_request_weather WHERE request_id = ?", f.id)).isEqualTo(requestWeather)
        val outfit = http("GET", f.owner, path = "/api/outfits/$outfitId")
        assertThat(outfit.statusCode()).isEqualTo(200)
        val outfitBody = JsonPath.parse(outfit.body())
        assertThat(outfitBody.read<Number>("$.ownerId").toLong()).isEqualTo(f.owner)
        assertThat(outfitBody.read<Number>("$.authorId").toLong()).isEqualTo(f.stylist)
        assertThat(outfitBody.read<String>("$.source")).isEqualTo("STYLIST")
        assertThat(outfitBody.read<String>("$.explanation")).isEqualTo(explanation)
        assertThat(outfitBody.read<String>("$.name")).hasSize(300).startsWith("  ").endsWith("  ")
        assertThat(outfitBody.read<List<Number>>("$.itemIds").map { it.toLong() }).containsExactly(secondItem, f.item)
        assertThat(jdbc.queryForMap("SELECT * FROM outfit_weather WHERE outfit_id = ?", outfitId) - "outfit_id")
            .isEqualTo(requestWeather - "request_id")
        assertThat(http("GET", f.stylist, path = "/api/stylist/clients/${f.owner}/outfits/$outfitId").statusCode()).isEqualTo(200)
        listOf(f.owner to ownPath, f.stylist to clientPath(f.owner)).forEach { (actor, path) ->
            val listed = http("GET", actor, path = path)
            assertThat(listed.statusCode()).isEqualTo(200)
            assertThat(JsonPath.parse(listed.body()).read<Number>("$[0].answer.outfitId").toLong()).isEqualTo(outfitId)
        }
        assertThat(http("PUT", f.owner, """{"version":2}""", "$ownPath/${f.id}/cancellation").statusCode()).isEqualTo(409)
        assertThat(http("POST", f.stylist, payload(listOf(f.item), version = 2), answerPath(f)).statusCode()).isEqualTo(409)
        assertThat(count("outfit")).isEqualTo(1)
        assertThat(count("stylist_request_history")).isEqualTo(1)
    }

    @Test
    fun `answer replay preserves snapshot and old keyed creation retains its exact pending response`() {
        // given
        val f = fixture(createKey = "create")
        val original = http("POST", f.owner, createPayload(f.stylist), ownPath, "create")
        val body = payload(listOf(f.item))
        val created = http("POST", f.stylist, body, answerPath(f), "answer")

        // when
        val replay = http("POST", f.stylist, body, answerPath(f), "answer")
        val oldReplay = http("POST", f.owner, createPayload(f.stylist), ownPath, "create")
        val changed = http("POST", f.stylist, payload(listOf(f.item), explanation = "changed"), answerPath(f), "answer")

        // then
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.body()).isEqualTo(created.body())
        assertThat(replay.headers().firstValue("Location")).hasValue(requestPath(f))
        assertThat(oldReplay.statusCode()).isEqualTo(201)
        assertThat(oldReplay.body()).isEqualTo(original.body())
        assertThat(JsonPath.parse(oldReplay.body()).read<Map<String, Any?>>("$").keys)
            .containsExactlyInAnyOrder("id", "ownerId", "stylistId", "wishes", "weather", "status", "version", "createdAt", "modifiedAt")
        assertThat(changed.statusCode()).isEqualTo(409)
        assertThat(changed.body()).contains("idempotency_key_reused")
        assertThat(count("outfit")).isEqualTo(1)
        assertThat(count("stylist_request_answer")).isEqualTo(1)
        assertThat(count("stylist_request_history")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT response_json::text FROM idempotency_command WHERE operation = 'ANSWER_STYLIST_REQUEST'", String::class.java))
            .isNotBlank()

        // given
        jdbc.update("DELETE FROM access_grant WHERE owner_id = ?", f.owner)

        // when
        val revoked = http("POST", f.stylist, body, answerPath(f), "answer")

        // then
        assertThat(revoked.statusCode()).isEqualTo(404)
        assertThat(http("GET", f.owner, path = "$ownPath/${f.id}").body()).isEqualTo(created.body())
    }

    @Test
    fun `invalid answers and foreign items leave request pending without history or outfits`() {
        // given
        val f = fixture()
        val invalid = listOf(
            payload(emptyList()), payload(List(51) { f.item }), payload(listOf(f.item, f.item)),
            payload(listOf(0)), payload(listOf(-1)), payload(listOf(f.item)).replace("[${f.item}]", "[null]"),
            payload(listOf(f.item), name = " "), payload(listOf(f.item), name = "x".repeat(301)),
            payload(listOf(f.item), explanation = " "), payload(listOf(f.item), explanation = "x".repeat(2001)),
            payload(listOf(f.item), version = 0),
        )
        val foreignItem = item(user())

        // when
        val failures = invalid.map { http("POST", f.stylist, it, answerPath(f)).statusCode() }
        val foreign = http("POST", f.stylist, payload(listOf(foreignItem)), answerPath(f))
        val missing = http("POST", f.stylist, payload(listOf(Long.MAX_VALUE)), answerPath(f))

        // then
        assertThat(failures).containsOnly(400)
        assertThat(foreign.statusCode()).isEqualTo(404)
        assertThat(missing.statusCode()).isEqualTo(404)
        assertThat(current(f.id)["status"].toString()).isEqualTo("PENDING")
        assertThat(count("stylist_request_history")).isZero()
        assertThat(count("outfit")).isZero()
    }

    @Test
    fun `answer requires selected active stylist and hides wrong owner and recipient`() {
        // given
        val f = fixture()
        val other = user(UserRole.STYLIST)
        val otherOwner = user()
        jdbc.insertAccessGrant(f.owner, other)
        jdbc.insertAccessGrant(otherOwner, f.stylist)
        val body = payload(listOf(f.item))

        // when
        val wrongRecipient = http("POST", other, body, answerPath(f))
        val wrongOwner = http("POST", f.stylist, body, "${clientPath(otherOwner)}/${f.id}/answer")
        val missing = http("POST", f.stylist, body, "${clientPath(f.owner)}/999999/answer")
        val roles = listOf(f.owner, user(UserRole.ADMIN)).map { http("POST", it, body, answerPath(f)).statusCode() }

        // then
        assertThat(listOf(wrongRecipient, wrongOwner, missing).map { it.statusCode() }).containsOnly(404)
        assertThat(roles).containsOnly(403)

        // given
        doThrow(AccountUnavailableException()).`when`(accounts).requireStylistAccess(f.stylist, f.owner)

        // when
        val unavailable = http("POST", f.stylist, body, answerPath(f), "outage")

        // then
        assertThat(unavailable.statusCode()).isEqualTo(503)
        assertThat(count("idempotency_command")).isZero()
        assertThat(count("outfit")).isZero()
        assertThat(count("stylist_request_history")).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["archive", "weather", "composition", "answer", "state", "result"])
    fun `failure at each write boundary rolls back the entire answer and makes key terminal`(stage: String) {
        // given
        val f = fixture()
        val previous = current(f.id)
        val failure = DataIntegrityViolationException("forced $stage")
        when (stage) {
            "archive" -> doThrow(failure).`when`(requests).archive(f.id, f.owner, 1)
            "weather" -> doThrow(failure).`when`(weather).insert(any(OutfitWeather::class.java)
                ?: OutfitWeather(0, BigDecimal.ZERO, 1, BigDecimal.ZERO))
            "composition" -> doThrow(failure).`when`(composition).insertAll(anyList())
            "answer" -> doThrow(failure).`when`(answers).insert(anyLong(), anyLong())
            "state" -> doThrow(failure).`when`(requests).answer(f.id, f.owner, f.stylist, 1)
            "result" -> doThrow(failure).`when`(commands).succeed(anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(201))
        }
        val body = payload(listOf(f.item))

        // when
        val failed = http("POST", f.stylist, body, answerPath(f), "failed")
        val retry = http("POST", f.stylist, body, answerPath(f), "failed")

        // then
        assertThat(failed.statusCode()).isEqualTo(409)
        assertThat(retry.statusCode()).isEqualTo(409)
        assertThat(retry.body()).contains("idempotency_failed")
        assertThat(current(f.id)).isEqualTo(previous)
        for (table in listOf("stylist_request_history", "stylist_request_answer", "outfit", "outfit_weather", "outfit_item")) {
            assertThat(count(table)).`as`(table).isZero()
        }
        assertThat(jdbc.queryForMap("SELECT state, response_json FROM idempotency_command"))
            .containsEntry("state", "FAILED").containsEntry("response_json", null)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `owner and admin cannot delete answer outfit and failed deletion restores ratings and their history`(admin: Boolean) {
        // given
        val f = fixture()
        val answered = http("POST", f.stylist, payload(listOf(f.item)), answerPath(f))
        val outfitId = JsonPath.parse(answered.body()).read<Number>("$.answer.outfitId").toLong()
        jdbc.insertRating(outfitId, f.stylist)
        val rating = jdbc.queryForMap("SELECT * FROM outfit_rating WHERE outfit_id = ?", outfitId)
        val actor = if (admin) user(UserRole.ADMIN) else f.owner
        val path = if (admin) "/api/admin/users/${f.owner}/outfits/$outfitId" else "/api/outfits/$outfitId"

        // when
        val deleted = http("DELETE", actor, path = path)

        // then
        assertThat(deleted.statusCode()).isEqualTo(409)
        assertThat(jdbc.queryForMap("SELECT * FROM outfit_rating WHERE outfit_id = ?", outfitId)).isEqualTo(rating)
        assertThat(count("outfit_rating_history")).isZero()
        assertThat(count("outfit_item")).isEqualTo(1)
        assertThat(count("outfit_weather")).isEqualTo(1)
        assertThat(http("GET", f.owner, path = "$ownPath/${f.id}").body()).isEqualTo(answered.body())
    }

    @ParameterizedTest
    @ValueSource(strings = ["answer-answer", "answer-cancel", "cancel-answer"])
    fun `contending answer and cancellation wait on row lock and loser cannot leave orphan data`(scenario: String) {
        // given
        val f = fixture()
        val answer = { http("POST", f.stylist, payload(listOf(f.item)), answerPath(f)) }
        val cancel = { http("PUT", f.owner, """{"version":1}""", "$ownPath/${f.id}/cancellation") }
        val firstAnswers = scenario != "cancel-answer"
        val secondAnswers = scenario != "answer-cancel"

        // when
        val (first, second) = race(f, if (firstAnswers) answer else cancel, if (secondAnswers) answer else cancel)

        // then
        assertThat(first.statusCode()).isEqualTo(if (firstAnswers) 201 else 200)
        assertThat(second.statusCode()).isEqualTo(409)
        assertThat(current(f.id)["version"]).isEqualTo(2L)
        assertThat(current(f.id)["status"].toString()).isEqualTo(if (firstAnswers) "ANSWERED" else "CANCELLED")
        assertThat(count("stylist_request_history")).isEqualTo(1)
        val expected = if (firstAnswers) 1L else 0L
        for (table in listOf("stylist_request_answer", "outfit", "outfit_item", "outfit_weather")) {
            assertThat(count(table)).`as`(table).isEqualTo(expected)
        }
    }

    @Test
    fun `already authorized answer finishes after grant revocation while next keyed replay is denied`() {
        // given
        val f = fixture()
        val body = payload(listOf(f.item))
        val answer = { http("POST", f.stylist, body, answerPath(f), "revoked") }
        val cancel = { http("PUT", f.owner, """{"version":1}""", "$ownPath/${f.id}/cancellation") }

        // when
        val (answered, cancelled) = race(f, answer, cancel) {
            jdbc.update("DELETE FROM access_grant WHERE owner_id = ?", f.owner)
        }
        val replay = answer()

        // then
        assertThat(answered.statusCode()).isEqualTo(201)
        assertThat(cancelled.statusCode()).isEqualTo(409)
        assertThat(replay.statusCode()).isEqualTo(404)
        assertThat(count("outfit")).isEqualTo(1)
    }

    @Test
    fun `in-progress answer key rejects concurrent retry and then replays one committed answer`() {
        // given
        val f = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
        }.`when`(requests).findLocked(f.id, f.owner)
        val body = payload(listOf(f.item))
        Executors.newSingleThreadExecutor().use { executor ->
            val first = executor.submit(Callable { http("POST", f.stylist, body, answerPath(f), "parallel") })
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()

                // when
                val second = http("POST", f.stylist, body, answerPath(f), "parallel")
                release.countDown()
                val created = first.get(20, TimeUnit.SECONDS)
                val replay = http("POST", f.stylist, body, answerPath(f), "parallel")

                // then
                assertThat(second.statusCode()).isEqualTo(409)
                assertThat(second.body()).contains("idempotency_in_progress")
                assertThat(created.statusCode()).isEqualTo(201)
                assertThat(replay.body()).isEqualTo(created.body())
                assertThat(count("outfit")).isEqualTo(1)
                assertThat(count("stylist_request_history")).isEqualTo(1)
            } finally {
                release.countDown()
            }
        }
    }

    private fun race(
        f: Fixture,
        first: () -> HttpResponse<String>,
        second: () -> HttpResponse<String>,
        whileWaiting: () -> Unit = {},
    ): Pair<HttpResponse<String>, HttpResponse<String>> {
        val locked = CountDownLatch(1)
        val secondEntering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val isFirst = AtomicBoolean(true)
        doAnswer { call ->
            val firstCall = isFirst.compareAndSet(true, false)
            if (!firstCall) secondEntering.countDown()
            val row = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
            if (firstCall) {
                check(row != null)
                locked.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            row
        }.`when`(requests).findLocked(f.id, f.owner)
        Executors.newFixedThreadPool(2).use { executor ->
            val firstResult = executor.submit(Callable { first() })
            try {
                check(locked.await(10, TimeUnit.SECONDS))
                val secondResult = executor.submit(Callable { second() })
                check(secondEntering.await(10, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (jdbc.queryForObject("""SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                        AND query LIKE '%stylist_request%' AND wait_event_type = 'Lock')""", Boolean::class.java) != true) {
                    check(System.nanoTime() < deadline) { "Second request did not wait on PostgreSQL row lock" }
                    Thread.yield()
                }
                whileWaiting()
                release.countDown()
                return firstResult.get(20, TimeUnit.SECONDS) to secondResult.get(20, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
        }
    }

    private data class Fixture(val owner: Long, val stylist: Long, val id: Long, val item: Long)

    private fun fixture(createKey: String? = null): Fixture {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        val created = http("POST", owner, createPayload(stylist), ownPath, createKey)
        assertThat(created.statusCode()).isEqualTo(201)
        return Fixture(owner, stylist, JsonPath.parse(created.body()).read<Number>("$.id").toLong(), item(owner))
    }

    private fun user(role: UserRole = UserRole.USER) = jdbc.insertUser(UUID.randomUUID().toString(), "!", role).id
    private fun item(owner: Long) = jdbc.insertItem(owner, CreateWardrobeItemRequest("Shirt", 1, "blue", "cotton")).id
    private fun current(id: Long) = jdbc.queryForMap("SELECT * FROM stylist_request WHERE id = ?", id)
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun clientPath(owner: Long) = "/api/stylist/clients/$owner/requests"
    private fun requestPath(f: Fixture) = "${clientPath(f.owner)}/${f.id}"
    private fun answerPath(f: Fixture) = "${requestPath(f)}/answer"
    private fun createPayload(stylist: Long) =
        """{"stylistId":$stylist,"weather":{"temperatureC":-2.5,"precipitationTypeId":1,"windSpeedMps":1.25}}"""
    private fun payload(ids: List<Long>, version: Long = 1, name: String = "Daily", explanation: String = "  Suitable  ") =
        """{"version":$version,"name":"$name","itemIds":[${ids.joinToString(",")}],"explanation":"$explanation"}"""

    private fun http(method: String, actor: Long, body: String? = null, path: String, key: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(25)).header("Authorization", bearer(actor.toString()))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (key != null) request.header("Idempotency-Key", key)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
