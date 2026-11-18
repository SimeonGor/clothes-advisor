package ru.itmo.clothesadvisor.recommendationservice.request

import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.core.publisher.Flux
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionStylePreferences
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionWeather
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test", "request-store-test")
class RecommendationRequestStoreTests {
    @Autowired private lateinit var store: RecommendationRequestStore

    @Test
    fun `concurrent identical deliveries commit one request`() {
        // given
        val request = command()

        // when
        val results = Flux.range(1, 20).flatMap({ store.accept(request) }, 20)
            .collectList().block(Duration.ofSeconds(20))!!

        // then
        assertThat(results.count { it == RequestAcceptance.ACCEPTED }).isEqualTo(1)
        assertThat(results.count { it == RequestAcceptance.DUPLICATE }).isEqualTo(19)
        assertThat(text("SELECT count(*) FROM recommendation_request WHERE request_id = '${request.requestId}'"))
            .isEqualTo("1")
    }

    @Test
    fun `conflicting concurrent deliveries preserve exactly the winning input`() {
        // given
        val first = command()
        val other = first.copy(input = first.input.copy(occasion = "Travel"))

        // when
        val results = Flux.just(first, other)
            .flatMap { request -> store.accept(request).map { request to it } }
            .collectList().block(Duration.ofSeconds(20))!!

        // then
        assertThat(results.map { it.second })
            .containsExactlyInAnyOrder(RequestAcceptance.ACCEPTED, RequestAcceptance.INPUT_CONFLICT)
        val winner = results.single { it.second == RequestAcceptance.ACCEPTED }.first
        assertThat(text("SELECT input->>'occasion' FROM recommendation_request WHERE request_id = '${first.requestId}'"))
            .isEqualTo(winner.input.occasion)
    }

    @Test
    fun `replay compares stored typed values and preserves original order and terminal state`() {
        // given
        val request = command()
        assertThat(accept(request)).isEqualTo(RequestAcceptance.ACCEPTED)
        val resultEvent = UUID.randomUUID()
        connection().use { db ->
            db.prepareStatement(
                """
                UPDATE recommendation_request SET state = 'SUCCEEDED', version = 2, completed_at = CURRENT_TIMESTAMP,
                    result = '{"itemIds":[2],"explanation":"chosen"}', result_event_id = ?
                WHERE request_month = ? AND request_id = ?
                """,
            ).use { update ->
                update.setObject(1, resultEvent)
                update.setObject(2, request.requestMonth)
                update.setObject(3, request.requestId)
                assertThat(update.executeUpdate()).isEqualTo(1)
            }
        }
        val input = request.input
        val replay = request.copy(
            acceptedAt = request.acceptedAt.plusSeconds(1),
            input = input.copy(
                weather = input.weather.copy(temperatureC = BigDecimal("2.0000"), windSpeedMps = BigDecimal("0")),
                candidates = input.candidates.reversed(),
                requiredItemIds = input.requiredItemIds!!.reversed(),
            ),
        )

        // when
        val result = accept(replay)

        // then
        assertThat(result).isEqualTo(RequestAcceptance.DUPLICATE)
        val condition = "FROM recommendation_request WHERE request_id = '${request.requestId}'"
        assertThat(text("SELECT input->'candidates'->0->>'itemId' $condition")).isEqualTo("2")
        assertThat(text("SELECT input->'requiredItemIds'->>0 $condition")).isEqualTo("2")
        assertThat(text("SELECT concat_ws('/', state, version, result_event_id) $condition"))
            .isEqualTo("SUCCEEDED/2/$resultEvent")
    }

    @Test
    fun `each semantic command field conflicts without replacing original snapshot`() {
        // given
        val request = command()
        val input = request.input
        val candidate = input.candidates.first()
        val preferences = input.stylePreferences!!
        assertThat(accept(request)).isEqualTo(RequestAcceptance.ACCEPTED)
        val candidates = listOf(
            candidate.copy(name = candidate.name + " "), candidate.copy(categoryCode = "B"),
            candidate.copy(categoryName = "Jacket"), candidate.copy(color = "Green"),
            candidate.copy(material = "Linen"), candidate.copy(photoId = 17),
            candidate.copy(fileId = UUID.randomUUID()), candidate.copy(contentType = "image/png"),
            candidate.copy(sizeBytes = candidate.sizeBytes + 1),
        )
        val changedInputs = candidates.map { input.copy(candidates = listOf(it) + input.candidates.drop(1)) } +
            listOf(
                input.copy(candidates = input.candidates + candidate.copy(itemId = 8, photoId = 18)),
                input.copy(occasion = "work"), input.copy(preferredStyle = ""),
                input.copy(requiredItemIds = listOf(1)), input.copy(stylePreferences = null),
                input.copy(stylePreferences = preferences.copy(preferredColorsAndMaterials = "other")),
                input.copy(stylePreferences = preferences.copy(avoidances = "other")),
                input.copy(stylePreferences = preferences.copy(additionalWishes = "other")),
                input.copy(weather = input.weather.copy(temperatureC = BigDecimal("2.0001"))),
                input.copy(weather = input.weather.copy(windSpeedMps = BigDecimal.ONE)),
                input.copy(weather = input.weather.copy(precipitationCode = "snow")),
                input.copy(weather = input.weather.copy(precipitationName = "Rain")),
            )
        val changed = changedInputs.map { request.copy(input = it) } + listOf(
            request.copy(ownerId = request.ownerId + 1),
            request.copy(startDeadline = request.startDeadline.plusNanos(1000)),
        )

        // when
        val results = changed.map(::accept)

        // then
        assertThat(results).containsOnly(RequestAcceptance.INPUT_CONFLICT)
        assertThat(accept(request)).isEqualTo(RequestAcceptance.DUPLICATE)
    }

    @Test
    fun `empty and omitted required IDs have the same identity while selection goals differ`() {
        // given
        val command = command()
        val original = command.copy(input = command.input.copy(requiredItemIds = null))
        assertThat(accept(original)).isEqualTo(RequestAcceptance.ACCEPTED)

        // when
        val replay = accept(original.copy(input = original.input.copy(requiredItemIds = emptyList())))
        val capsule = accept(original.copy(goal = SelectionGoal.CAPSULE))

        // then
        assertThat(replay).isEqualTo(RequestAcceptance.DUPLICATE)
        assertThat(capsule).isEqualTo(RequestAcceptance.INPUT_CONFLICT)
    }

    @Test
    fun `invalid and unsupported commands cannot create a request`() {
        // given
        val request = command()
        val input = request.input
        val invalid = listOf(
            request.copy(input = input.copy(candidates = input.candidates + input.candidates.first())),
            request.copy(input = input.copy(requiredItemIds = listOf(1, 1))),
            request.copy(input = input.copy(requiredItemIds = listOf(50))),
            request.copy(input = input.copy(candidates = emptyList())),
            request.copy(requestMonth = request.requestMonth.plusDays(1)),
            request.copy(acceptedAt = request.acceptedAt.minusSeconds(1)),
        )

        // when
        val results = invalid.map(::accept)
        val unsupported = accept(request.copy(inputVersion = 2))

        // then
        assertThat(results).containsOnly(RequestAcceptance.INVALID_INPUT)
        assertThat(unsupported).isEqualTo(RequestAcceptance.UNSUPPORTED_INPUT_VERSION)
        assertThat(text("SELECT count(*) FROM recommendation_request WHERE request_id = '${request.requestId}'"))
            .isEqualTo("0")
    }

    @Test
    fun `original UTC month distinguishes operations and late deliveries reuse their original partition`() {
        // given
        val now = currentMonth()
        val original = command(now.minusMonths(1))
        val next = command(now).copy(requestId = original.requestId)
        val previousInstant = next.acceptedAt.minusNanos(1000)
        val previous = original.copy(acceptedAt = previousInstant, startDeadline = next.startDeadline)

        // when
        val results = listOf(previous, next, previous).map(::accept)

        // then
        assertThat(results).containsExactly(RequestAcceptance.ACCEPTED, RequestAcceptance.ACCEPTED, RequestAcceptance.DUPLICATE)
        assertThat(text("SELECT count(DISTINCT tableoid) FROM recommendation_request WHERE request_id = '${original.requestId}'"))
            .isEqualTo("2")
    }

    @Test
    fun `first open month is inclusive and future partition rejects early deliveries`() {
        // given
        val now = currentMonth()
        val first = command(now.minusMonths(3))
        val before = command(now.minusMonths(4))
        val future = command(now.plusMonths(1))

        // when
        val results = listOf(first, before, future).map(::accept)

        // then
        assertThat(results).containsExactly(
            RequestAcceptance.ACCEPTED, RequestAcceptance.REQUEST_PERIOD_CLOSED, RequestAcceptance.FUTURE_REQUEST_PERIOD,
        )
        assertThat(text("SELECT count(*) FROM pg_inherits WHERE inhparent = 'recommendation_request'::regclass")).isEqualTo("5")
        assertThat(text("SELECT to_regclass('recommendation_request_${future.requestMonth.toString().take(7).replace('-', '_')}') IS NOT NULL"))
            .isEqualTo("t")
    }

    @Test
    fun `durable closing barrier prevents both insertion and replay before physical detach`() {
        // given
        val oldest = currentMonth().minusMonths(3)
        val previous = command(oldest)
        assertThat(accept(previous)).isEqualTo(RequestAcceptance.ACCEPTED)

        connection().use { db ->
            try {
                db.autoCommit = false
                db.prepareStatement("UPDATE request_retention SET closed_before_month = ?").use {
                    it.setObject(1, oldest.plusMonths(1))
                    it.executeUpdate()
                }

                // when
                val concurrentReplay = store.accept(previous).toFuture()
                db.commit()
                val replay = concurrentReplay.get(20, java.util.concurrent.TimeUnit.SECONDS)
                val late = accept(command(oldest))

                // then
                assertThat(replay).isEqualTo(RequestAcceptance.REQUEST_PERIOD_CLOSED)
                assertThat(late).isEqualTo(RequestAcceptance.REQUEST_PERIOD_CLOSED)
                assertThat(text("SELECT count(*) FROM recommendation_request WHERE request_id = '${previous.requestId}'"))
                    .isEqualTo("1")
            } finally {
                db.rollback()
                db.prepareStatement("UPDATE request_retention SET closed_before_month = ?").use {
                    it.setObject(1, oldest)
                    it.executeUpdate()
                }
                db.commit()
            }
        }
    }

    private fun command(month: LocalDate = currentMonth()): RecommendationRequested {
        val accepted = month.atStartOfDay().toInstant(ZoneOffset.UTC)
        val candidates = listOf(2L, 1L).map { id ->
            RecommendationCandidate(id, "Item $id", "A", "Shirt", "Blue", "Cotton",
                10 + id, UUID.randomUUID(), "image/jpeg", 250)
        }
        return RecommendationRequested(
            month, UUID.randomUUID(), 15, 1, SelectionGoal.OUTFIT, accepted, accepted.plusSeconds(900),
            RecommendationInput(
                ItemSelectionWeather(BigDecimal("2.00"), BigDecimal("0.00"), "NONE", "None"),
                candidates, "Work", null, listOf(2, 1), ItemSelectionStylePreferences("Blue", "Leather", "Warm"),
            ),
        )
    }

    private fun accept(command: RecommendationRequested) = store.accept(command).block(Duration.ofSeconds(10))!!

    private fun currentMonth(): LocalDate = LocalDate.parse(
        text("SELECT date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date"),
    )

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun text(sql: String) = connection().use { db ->
        db.createStatement().use { query ->
            query.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getString(1)!!
            }
        }
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() = postgres.stop()
    }
}
