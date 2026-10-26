package ru.itmo.clothesadvisor.controller.stylistrequest

import com.jayway.jsonpath.JsonPath
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertAccessGrant
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.stylistrequest.CancelStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CreateStylistRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestWeather
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import ru.itmo.clothesadvisor.repository.stylistrequest.StylistRequestRepository
import ru.itmo.clothesadvisor.service.stylistrequest.StylistRequestService

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class StylistRequestIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var service: StylistRequestService
    @MockitoSpyBean private lateinit var repository: StylistRequestRepository
    @MockitoSpyBean private lateinit var commands: IdempotencyCommandRepository
    private val client = HttpClient.newHttpClient()
    private val ownPath = "/api/me/stylist-requests"

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `creation returns database snapshot and lists only owner and selected recipient with pagination`() {
        // given
        val owner = user()
        val otherOwner = user()
        val stylist = user(UserRole.STYLIST)
        val otherStylist = user(UserRole.STYLIST)
        listOf(stylist, otherStylist).forEach { jdbc.insertAccessGrant(owner, it) }
        jdbc.insertAccessGrant(otherOwner, stylist)
        val created = http("POST", owner, payload(stylist, "x".repeat(2000)))
        val first = id(created)
        val second = id(http("POST", owner, payload(otherStylist)))
        http("POST", otherOwner, payload(stylist))

        // when
        val own = http("GET", owner, path = "$ownPath?page=1&size=1")
        val recipient = http("GET", stylist, path = clientPath(owner))
        val fetched = http("GET", owner, path = "$ownPath/$first")

        // then
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(created.headers().firstValue("Location")).hasValue("$ownPath/$first")
        assertThat(fetched.body()).isEqualTo(created.body())
        val body = JsonPath.parse(created.body())
        assertThat(body.read<String>("$.wishes")).hasSize(2000)
        assertThat(body.read<String>("$.status")).isEqualTo("PENDING")
        for ((field, column) in listOf("createdAt" to "created_at", "modifiedAt" to "modified_at")) {
            assertThat(Instant.parse(body.read<String>("$.$field"))).isEqualTo(
                jdbc.queryForObject("SELECT $column FROM stylist_request WHERE id = ?", Timestamp::class.java, first)!!.toInstant(),
            )
        }
        assertThat(jdbc.queryForMap("SELECT * FROM stylist_request_weather WHERE request_id = ?", first))
            .containsEntry("temperature_c", BigDecimal("-2.5")).containsEntry("wind_speed_mps", BigDecimal("1.25"))
        assertThat(own.headers().firstValue("X-Total-Count")).hasValue("2")
        assertThat(JsonPath.parse(own.body()).read<List<Int>>("$[*].id")).containsExactly(first.toInt())
        assertThat(recipient.headers().firstValue("X-Total-Count")).hasValue("1")
        assertThat(JsonPath.parse(recipient.body()).read<List<Int>>("$[*].id")).containsExactly(first.toInt())
        assertThat(http("GET", stylist, path = "${clientPath(owner)}/$second").statusCode()).isEqualTo(404)
        assertThat(http("GET", otherOwner, path = "$ownPath/$first").statusCode()).isEqualTo(404)
        assertThat(http("PUT", otherOwner, """{"version":1}""", "$ownPath/$first/cancellation").statusCode()).isEqualTo(404)
        assertThat(http("GET", owner, path = "$ownPath?size=51").statusCode()).isEqualTo(400)
    }

    @Test
    fun `missing and null wishes normalize to empty without changing weather`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        val omitted = payload(stylist).replace(",\"wishes\":\"\"", "")
        val explicitNull = payload(stylist).replace("\"wishes\":\"\"", "\"wishes\":null")

        // when
        val results = listOf(omitted, explicitNull).map { http("POST", owner, it) }

        // then
        assertThat(results.map { it.statusCode() }).containsOnly(201)
        assertThat(results.map { JsonPath.parse(it.body()).read<String>("$.wishes") }).containsOnly("")
        assertThat(count("stylist_request_weather")).isEqualTo(2)
    }

    @Test
    fun `approved remote identities do not require local account rows`() {
        // given
        val owner = 900001L
        val stylist = 900002L
        doAnswer { null }.`when`(accounts).requireStylistAccess(stylist, owner)
        val request = CreateStylistRequest(stylist, OutfitWeatherDto(BigDecimal.TEN, 1, BigDecimal.ZERO))

        // when
        val created = service.create(actor(owner), request)

        // then
        assertThat(created.ownerId).isEqualTo(owner)
        assertThat(created.stylistId).isEqualTo(stylist)
        assertThat(count("app_user")).isZero()
        assertThat(count("stylist_request_weather")).isEqualTo(1)
    }

    @Test
    fun `grant revocation denies recipient and keyed replay but preserves own read and cancellation`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        val created = http("POST", owner, payload(stylist), key = "request")
        val id = id(created)
        assertThat(http("GET", stylist, path = "${clientPath(owner)}/$id").statusCode()).isEqualTo(200)
        jdbc.update("DELETE FROM access_grant WHERE owner_id = ?", owner)

        // when
        val replay = http("POST", owner, payload(stylist), key = "request")
        val recipient = http("GET", stylist, path = clientPath(owner))
        val own = http("GET", owner, path = "$ownPath/$id")
        val cancelled = http("PUT", owner, """{"version":1}""", "$ownPath/$id/cancellation")

        // then
        assertThat(replay.statusCode()).isEqualTo(404)
        assertThat(recipient.statusCode()).isEqualTo(404)
        assertThat(own.statusCode()).isEqualTo(200)
        assertThat(cancelled.statusCode()).isEqualTo(200)
        jdbc.insertAccessGrant(owner, stylist)
        assertThat(JsonPath.parse(http("GET", stylist, path = "${clientPath(owner)}/$id").body())
            .read<String>("$.status")).isEqualTo("CANCELLED")
    }

    @Test
    fun `roles validation and unavailable account preserve empty storage`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val admin = user(UserRole.ADMIN)
        jdbc.insertAccessGrant(owner, stylist)

        // when
        val invalid = listOf(payload(stylist, "x".repeat(2001)), payload(stylist).replace("1.25", "-1"),
            payload(stylist).replace("\"precipitationTypeId\":1", "\"precipitationTypeId\":999"))
            .map { http("POST", owner, it).statusCode() }

        // then
        assertThat(invalid).containsOnly(400)
        for (actor in listOf(stylist, admin)) {
            assertThat(http("POST", actor, payload(stylist)).statusCode()).isEqualTo(403)
            assertThat(http("GET", actor).statusCode()).isEqualTo(403)
        }
        assertThat(http("GET", admin, path = clientPath(owner)).statusCode()).isEqualTo(403)
        assertThat(http("POST", user(), payload(stylist)).statusCode()).isEqualTo(404)

        // given
        doThrow(AccountUnavailableException()).`when`(accounts).requireStylistAccess(stylist, owner)

        // when
        val create = http("POST", owner, payload(stylist))
        val read = http("GET", stylist, path = clientPath(owner))

        // then
        assertThat(create.statusCode()).isEqualTo(503)
        assertThat(read.statusCode()).isEqualTo(503)
        assertThat(count("stylist_request")).isZero()
    }

    @Test
    fun `cancellation archives exact prior row and preserves weather and no-op dates`() {
        // given
        val (owner, id) = create()
        jdbc.update("UPDATE stylist_request SET created_at = '2025-01-01T00:00:00Z', modified_at = '2025-02-01T00:00:00Z' WHERE id = ?", id)
        val previous = current(id)
        val weather = jdbc.queryForMap("SELECT * FROM stylist_request_weather WHERE request_id = ?", id)

        // when
        val cancelled = http("PUT", owner, """{"version":1}""", "$ownPath/$id/cancellation")
        val noOp = http("PUT", owner, """{"version":2}""", "$ownPath/$id/cancellation")
        val stale = http("PUT", owner, """{"version":1}""", "$ownPath/$id/cancellation")

        // then
        assertThat(cancelled.statusCode()).isEqualTo(200)
        assertThat(noOp.body()).isEqualTo(cancelled.body())
        assertThat(stale.statusCode()).isEqualTo(409)
        val archived = jdbc.queryForMap("SELECT * FROM stylist_request_history WHERE request_id = ?", id)
        assertThat(archived - setOf("request_id", "archived_at")).isEqualTo(previous - "id")
        assertThat(archived["archived_at"]).isEqualTo(current(id)["modified_at"])
        assertThat(current(id) - setOf("status", "version", "modified_at"))
            .isEqualTo(previous - setOf("status", "version", "modified_at"))
        assertThat(jdbc.queryForMap("SELECT * FROM stylist_request_weather WHERE request_id = ?", id)).isEqualTo(weather)
        assertThat(count("stylist_request_history")).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `archive and mutation failures roll back cancellation`(failArchive: Boolean) {
        // given
        val (owner, id) = create()
        val previous = current(id)
        if (failArchive) doThrow(DataIntegrityViolationException("forced")).`when`(repository).archive(id, owner, 1)
        else doThrow(DataIntegrityViolationException("forced")).`when`(repository).cancel(id, owner, 1)

        // when
        val failure = catchThrowable { service.cancel(actor(owner), id, CancelStylistRequest(1)) }

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(current(id)).isEqualTo(previous)
        assertThat(count("stylist_request_history")).isZero()
    }

    @Test
    fun `concurrent cancellations write one history row and reject the stale contender`() {
        // given
        val (owner, id) = create()
        val barrier = CyclicBarrier(2)
        doAnswer { call ->
            barrier.await(10, TimeUnit.SECONDS)
            mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
        }.`when`(repository).findLocked(id, owner)
        val executor = Executors.newFixedThreadPool(2)
        try {
            // when
            val results = (1..2).map { executor.submit(Callable {
                catchThrowable { service.cancel(actor(owner), id, CancelStylistRequest(1)) }
            }) }.map { it.get(20, TimeUnit.SECONDS) }

            // then
            assertThat(results.filterNotNull()).singleElement().isInstanceOf(OptimisticLockingFailureException::class.java)
            assertThat(results.count { it == null }).isEqualTo(1)
            assertThat(current(id)["version"]).isEqualTo(2L)
            assertThat(count("stylist_request_history")).isEqualTo(1)
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `keyed creation replays original snapshot and rejects changed payload`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        val created = http("POST", owner, payload(stylist), key = "same")
        http("PUT", owner, """{"version":1}""", "$ownPath/${id(created)}/cancellation")

        // when
        val replay = http("POST", owner, payload(stylist), key = "same")
        val conflict = http("POST", owner, payload(stylist, "changed"), key = "same")

        // then
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.body()).isEqualTo(created.body())
        assertThat(conflict.statusCode()).isEqualTo(409)
        assertThat(count("stylist_request")).isEqualTo(1)
        assertThat(count("stylist_request_weather")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_command", String::class.java)).isEqualTo("SUCCEEDED")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `weather or result failure rolls back all created data and makes key terminal`(failResult: Boolean) {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        val request = CreateStylistRequest(stylist, OutfitWeatherDto(BigDecimal.TEN, 1, BigDecimal.ZERO))
        if (failResult) doThrow(DataIntegrityViolationException("forced")).`when`(commands).succeed(anyLong(), anyString())
        else doThrow(DataIntegrityViolationException("forced")).`when`(repository).insertWeather(
            1L, StylistRequestWeather(BigDecimal.TEN, 1, BigDecimal.ZERO),
        )

        // when
        val failure = catchThrowable { service.create(actor(owner), request, "failure") }
        val replay = catchThrowable { service.create(actor(owner), request, "failure") }

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(replay).hasMessage("idempotency_failed")
        assertThat(count("stylist_request")).isZero()
        assertThat(count("stylist_request_weather")).isZero()
        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_command", String::class.java)).isEqualTo("FAILED")
    }

    @Test
    fun `concurrent identical key permits one creation and in-progress conflict`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
        }.`when`(repository).create(owner, stylist, "")
        val executor = Executors.newSingleThreadExecutor()
        try {
            // when
            val first = executor.submit(Callable { http("POST", owner, payload(stylist), key = "parallel") })
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
            val second = http("POST", owner, payload(stylist), key = "parallel")
            release.countDown()

            // then
            assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(201)
            assertThat(second.statusCode()).isEqualTo(409)
            assertThat(second.body()).contains("idempotency_in_progress")
            assertThat(count("stylist_request")).isEqualTo(1)
            assertThat(count("stylist_request_weather")).isEqualTo(1)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun create(): Pair<Long, Long> {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        return owner to id(http("POST", owner, payload(stylist)))
    }

    private fun user(role: UserRole = UserRole.USER) = jdbc.insertUser(UUID.randomUUID().toString(), "!", role).id
    private fun actor(owner: Long) = CurrentUser(owner, "owner", UserRole.USER)
    private fun clientPath(owner: Long) = "/api/stylist/clients/$owner/requests"
    private fun id(response: HttpResponse<String>) = JsonPath.parse(response.body()).read<Number>("$.id").toLong()
    private fun current(id: Long) = jdbc.queryForMap("SELECT * FROM stylist_request WHERE id = ?", id)
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun payload(stylist: Long, wishes: String = "") =
        """{"stylistId":$stylist,"weather":{"temperatureC":-2.5,"precipitationTypeId":1,"windSpeedMps":1.25},"wishes":"$wishes"}"""

    private fun http(method: String, actor: Long, body: String? = null, path: String = ownPath, key: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("X-User-Id", actor.toString())
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (key != null) request.header("Idempotency-Key", key)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
