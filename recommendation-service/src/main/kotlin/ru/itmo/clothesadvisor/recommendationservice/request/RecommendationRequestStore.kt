package ru.itmo.clothesadvisor.recommendationservice.request

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.observability.DurableTrace
import ru.itmo.clothesadvisor.recommendationservice.config.SelectionJsonConfiguration

internal data class RecommendationFinalization(val id: UUID, val month: LocalDate, val owner: Long, val outcome: String)

@Repository
@ConditionalOnProperty("spring.r2dbc.url")
class RecommendationRequestStore(
    private val database: DatabaseClient,
    transactionManager: ReactiveTransactionManager,
) {
    private val transaction = TransactionalOperator.create(transactionManager)
    private val json = SelectionJsonConfiguration.mapper()

    // The outer transaction keeps the shared retention lock from admission through terminal replay.
    internal fun acceptDelivery(
        command: RecommendationRequested, traceparent: String? = null, onRejected: (RequestAcceptance) -> Mono<Void>,
    ): Mono<Void> = accept(command, traceparent).flatMap { decision ->
        when (decision) {
            RequestAcceptance.ACCEPTED -> Mono.empty()
            RequestAcceptance.DUPLICATE -> requestReplay(command)
            else -> onRejected(decision)
        }
    }.let(transaction::transactional)

    private fun requestReplay(command: RecommendationRequested): Mono<Void> = database.sql(
        """
        INSERT INTO outbox_event (event_id, type, schema_version, aggregate_id, payload, created_at)
        SELECT result_event_id, 'RecommendationFinished', 1, request_id::text,
            jsonb_build_object('requestId', request_id, 'requestMonth', request_month, 'ownerId', owner_id, 'outcome', state)
            || CASE WHEN state = 'SUCCEEDED'
                THEN jsonb_build_object('itemIds', result->'itemIds', 'explanation', result->'explanation')
                ELSE jsonb_build_object('errorCode', error_code) END,
            completed_at
        FROM recommendation_request
        WHERE request_month = :month AND request_id = :id AND state IN ('SUCCEEDED','FAILED','UNKNOWN')
        ON CONFLICT (event_id) DO UPDATE SET published_at = NULL, next_attempt_at = clock_timestamp()
        WHERE outbox_event.published_at IS NOT NULL
        """,
    ).bind("month", command.requestMonth).bind("id", command.requestId).fetch().rowsUpdated().then()

    internal fun applyFinalization(ack: RecommendationFinalization): Mono<Boolean> {
        val identity = "request_month=:month AND request_id=:id AND owner_id=:owner"
        val compatible = "(state=:outcome OR (state='SUCCEEDED' AND :outcome='FAILED'))"
        fun DatabaseClient.GenericExecuteSpec.bindAck() = bind("month", ack.month).bind("id", ack.id)
            .bind("owner", ack.owner).bind("outcome", ack.outcome)
        return database.sql(
            """UPDATE recommendation_request SET applied_at=COALESCE(applied_at,clock_timestamp()),
               application_outcome=:outcome,version=version+1,modified_at=clock_timestamp()
               WHERE $identity AND $compatible AND application_outcome IS NULL""",
        ).bindAck().fetch().rowsUpdated().flatMap { updated ->
            if (updated != 0L) Mono.just(true) else database.sql(
                """SELECT EXISTS(SELECT FROM recommendation_request WHERE $identity AND $compatible
                   AND applied_at IS NOT NULL AND application_outcome=:outcome)""",
            ).bindAck().map { row, _ -> row.get(0, Boolean::class.javaObjectType)!! }.one()
        }
    }

    fun accept(command: RecommendationRequested, traceparent: String? = DurableTrace.capture()): Mono<RequestAcceptance> = Mono.defer {
        if (command.inputVersion != 1) return@defer Mono.just(RequestAcceptance.UNSUPPORTED_INPUT_VERSION)
        if (!command.validForAdmission()) return@defer Mono.just(RequestAcceptance.INVALID_INPUT)
        val inputJson = json.writeValueAsString(command.input)

        // Closing a period takes the conflicting row lock before detaching its partition.
        database.sql(
            """
            SELECT closed_before_month, date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date AS current_month,
                   pg_input_is_valid(CAST(:input AS text), 'jsonb') AS valid_input
            FROM request_retention WHERE singleton FOR SHARE
            """,
        ).bind("input", inputJson).map { row, _ ->
            val closedBefore = row.get("closed_before_month", LocalDate::class.java)!!
            val currentMonth = row.get("current_month", LocalDate::class.java)!!
            when {
                row.get("valid_input", Boolean::class.javaObjectType) == false -> RequestAcceptance.INVALID_INPUT
                command.requestMonth < closedBefore -> RequestAcceptance.REQUEST_PERIOD_CLOSED
                command.requestMonth > currentMonth -> RequestAcceptance.FUTURE_REQUEST_PERIOD
                else -> RequestAcceptance.ACCEPTED
            }
        }.one().switchIfEmpty(Mono.error(IllegalStateException("Missing request retention policy")))
            .flatMap { decision ->
                if (decision == RequestAcceptance.ACCEPTED) insertOrCompare(command, inputJson, traceparent) else Mono.just(decision)
            }.let(transaction::transactional)
    }

    private fun insertOrCompare(command: RecommendationRequested, inputJson: String, traceparent: String?): Mono<RequestAcceptance> =
        database.sql(
            """
            INSERT INTO recommendation_request
                (request_month, request_id, owner_id, goal, input_version, accepted_at, start_deadline, input, traceparent)
            VALUES (:month, :id, :owner, :goal, :version, :accepted, :deadline, CAST(:input AS jsonb), :traceparent)
            ON CONFLICT (request_month, request_id) DO NOTHING
            """,
        ).bind("month", command.requestMonth)
            .bind("id", command.requestId)
            .bind("owner", command.ownerId)
            .bind("goal", command.goal.name)
            .bind("version", command.inputVersion)
            .bind("accepted", command.acceptedAt.atOffset(ZoneOffset.UTC))
            .bind("deadline", command.startDeadline.atOffset(ZoneOffset.UTC))
            .bind("input", inputJson)
            .let { if (traceparent == null) it.bindNull("traceparent", String::class.java) else it.bind("traceparent", traceparent) }
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
