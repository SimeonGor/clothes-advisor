package ru.itmo.clothesadvisor.recommendationservice.request

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.recommendationservice.config.SelectionJsonConfiguration

@Repository
@ConditionalOnProperty("spring.r2dbc.url")
class RecommendationRequestStore(
    private val database: DatabaseClient,
    transactionManager: ReactiveTransactionManager,
) {
    private val transaction = TransactionalOperator.create(transactionManager)
    private val json = SelectionJsonConfiguration.mapper()

    fun accept(command: RecommendationRequested): Mono<RequestAcceptance> = Mono.defer {
        if (command.inputVersion != 1) return@defer Mono.just(RequestAcceptance.UNSUPPORTED_INPUT_VERSION)
        if (!command.validForAdmission()) return@defer Mono.just(RequestAcceptance.INVALID_INPUT)

        // Closing a period takes the conflicting row lock before detaching its partition.
        database.sql(
            """
            SELECT closed_before_month, date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date AS current_month
            FROM request_retention WHERE singleton FOR SHARE
            """,
        ).map { row, _ ->
            val closedBefore = row.get("closed_before_month", LocalDate::class.java)!!
            val currentMonth = row.get("current_month", LocalDate::class.java)!!
            when {
                command.requestMonth < closedBefore -> RequestAcceptance.REQUEST_PERIOD_CLOSED
                command.requestMonth > currentMonth -> RequestAcceptance.FUTURE_REQUEST_PERIOD
                else -> RequestAcceptance.ACCEPTED
            }
        }.one().switchIfEmpty(Mono.error(IllegalStateException("Missing request retention policy")))
            .flatMap { period ->
                if (period == RequestAcceptance.ACCEPTED) insertOrCompare(command) else Mono.just(period)
            }.let(transaction::transactional)
    }

    private fun insertOrCompare(command: RecommendationRequested): Mono<RequestAcceptance> =
        database.sql(
            """
            INSERT INTO recommendation_request
                (request_month, request_id, owner_id, goal, input_version, accepted_at, start_deadline, input)
            VALUES (:month, :id, :owner, :goal, :version, :accepted, :deadline, CAST(:input AS jsonb))
            ON CONFLICT (request_month, request_id) DO NOTHING
            """,
        ).bind("month", command.requestMonth)
            .bind("id", command.requestId)
            .bind("owner", command.ownerId)
            .bind("goal", command.goal.name)
            .bind("version", command.inputVersion)
            .bind("accepted", command.acceptedAt.atOffset(ZoneOffset.UTC))
            .bind("deadline", command.startDeadline.atOffset(ZoneOffset.UTC))
            .bind("input", json.writeValueAsString(command.input))
            .fetch().rowsUpdated().flatMap { inserted ->
                if (inserted == 1L) Mono.just(RequestAcceptance.ACCEPTED) else compareExisting(command)
            }

    // A separate READ COMMITTED statement observes the winner of a concurrent insert.
    private fun compareExisting(command: RecommendationRequested): Mono<RequestAcceptance> =
        database.sql(
            """
            SELECT owner_id, goal, input_version, start_deadline, input::text AS input_json
            FROM recommendation_request WHERE request_month = :month AND request_id = :id
            """,
        ).bind("month", command.requestMonth)
            .bind("id", command.requestId)
            .map { row, _ ->
                val matches = row.get("input_version", Int::class.javaObjectType) == command.inputVersion &&
                    row.get("owner_id", Long::class.javaObjectType) == command.ownerId &&
                    row.get("goal", String::class.java) == command.goal.name &&
                    row.get("start_deadline", OffsetDateTime::class.java)!!.toInstant() == command.startDeadline &&
                    json.readValue(row.get("input_json", String::class.java)!!, RecommendationInput::class.java)
                        .comparableV1() == command.input.comparableV1()
                if (matches) RequestAcceptance.DUPLICATE else RequestAcceptance.INPUT_CONFLICT
            }.one().switchIfEmpty(Mono.error(IllegalStateException("Conflicting recommendation request disappeared")))
}
