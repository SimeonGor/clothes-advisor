package ru.itmo.clothesadvisor.recommendationservice.execution

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.client.ai.SelectionInstructions
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.recommendationservice.config.SelectionJsonConfiguration
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationInput

internal data class ExecutionJob(
    val month: LocalDate,
    val id: UUID,
    val goal: SelectionGoal,
    val input: RecommendationInput,
    val execution: UUID,
    val deadline: java.time.Instant,
)

/** Blocking entry points run on the controller's boundedElastic thread or the dedicated worker. */
@Repository
@ConditionalOnProperty("spring.r2dbc.url")
internal class ExecutionStore(private val db: DatabaseClient, manager: ReactiveTransactionManager) {
    private val tx = TransactionalOperator.create(manager)
    private val json = SelectionJsonConfiguration.mapper()

    private fun freeSlot() = db.sql("""
        SELECT connection_id FROM ai_execution_slot
        WHERE connection_id = 'personal' AND state = 'FREE' FOR UPDATE
    """).map { row, _ -> row.get(0, String::class.java)!! }.one()

    fun claimSync(): UUID? {
        val execution = UUID.randomUUID()
        return tx.execute {
            freeSlot().flatMap {
                db.sql("""
                    UPDATE ai_execution_slot SET state = 'RUNNING', execution_id = :execution, mode = 'SYNC',
                        phase = 'PREPARING', started_at = clock_timestamp(), modified_at = clock_timestamp()
                    WHERE connection_id = 'personal' AND state = 'FREE'
                        AND NOT EXISTS (SELECT 1 FROM recommendation_request WHERE state = 'RECEIVED'
                            AND start_deadline > clock_timestamp())
                """).bind("execution", execution).fetch().rowsUpdated().filter { it == 1L }.map { execution }
            }
        }.singleOrEmpty().block()
    }

    fun claimAsync(): ExecutionJob? {
        val execution = UUID.randomUUID()
        return tx.execute {
            freeSlot().flatMap {
                db.sql("""
                    SELECT request_month, request_id, goal, input::text AS input_json, start_deadline
                    FROM recommendation_request WHERE state = 'RECEIVED' AND start_deadline > clock_timestamp()
                    ORDER BY accepted_at, request_month, request_id LIMIT 1 FOR UPDATE
                """).map { row, _ ->
                    ExecutionJob(
                        row.get("request_month", LocalDate::class.java)!!, row.get("request_id", UUID::class.java)!!,
                        SelectionGoal.valueOf(row.get("goal", String::class.java)!!),
                        json.readValue(row.get("input_json", String::class.java)!!, RecommendationInput::class.java),
                        execution, row.get("start_deadline", OffsetDateTime::class.java)!!.toInstant(),
                    )
                }.one()
            }.flatMap { job ->
                db.sql("""
                    UPDATE ai_execution_slot SET state = 'RUNNING', execution_id = :execution, mode = 'ASYNC',
                        phase = 'PREPARING', started_at = clock_timestamp(), modified_at = clock_timestamp(),
                        request_month = :month, request_id = :id WHERE connection_id = 'personal'
                """).bind("execution", execution).bind("month", job.month).bind("id", job.id)
                    .fetch().rowsUpdated().then(
                        db.sql("""
                            UPDATE recommendation_request SET state = 'RUNNING', version = version + 1, modified_at = clock_timestamp()
                            WHERE request_month = :month AND request_id = :id AND state = 'RECEIVED'
                        """).bind("month", job.month).bind("id", job.id).fetch().rowsUpdated(),
                    ).map { check(it == 1L); job }
            }
        }.singleOrEmpty().block()
    }

    // Expire the waiting queue even when a prior ambiguous provider call has paused the connection.
    fun expireWaiting() {
        db.sql("""
            SELECT finish_recommendation(request_month, request_id, 'RECEIVED', 'FAILED', 'queue_expired', NULL)
            FROM (SELECT request_month, request_id FROM recommendation_request
                WHERE state = 'RECEIVED' AND start_deadline <= clock_timestamp()
                ORDER BY accepted_at LIMIT 32 FOR UPDATE SKIP LOCKED) AS expired
        """).fetch().rowsUpdated().block()
    }

    fun startSync(execution: UUID) = db.sql("""
        UPDATE ai_execution_slot SET phase = 'CALL_STARTED', modified_at = clock_timestamp()
        WHERE state = 'RUNNING' AND mode = 'SYNC' AND phase = 'PREPARING' AND execution_id = :execution
    """).bind("execution", execution).fetch().rowsUpdated().block() == 1L

    fun stillPreparing(job: ExecutionJob): Boolean = db.sql("""
        SELECT EXISTS (SELECT 1 FROM ai_execution_slot s JOIN recommendation_request r USING(request_month, request_id)
            WHERE s.execution_id = :execution AND s.phase = 'PREPARING' AND s.state = 'RUNNING'
                AND r.state = 'RUNNING' AND r.start_deadline > clock_timestamp())
    """).bind("execution", job.execution).map { row, _ -> row.get(0, Boolean::class.javaObjectType)!! }.one().block() == true

    fun startAsync(job: ExecutionJob, instructions: SelectionInstructions): Boolean =
        tx.execute {
            db.sql("""
                SELECT execution_id FROM ai_execution_slot
                WHERE state = 'RUNNING' AND mode = 'ASYNC' AND phase = 'PREPARING' AND execution_id = :execution FOR UPDATE
            """).bind("execution", job.execution).map { row, _ -> row.get(0, UUID::class.java)!! }.one()
                .flatMap {
                    db.sql("""
                        UPDATE recommendation_request SET model = :model, prompt_version = :version, prompt_text = :prompt,
                            modified_at = clock_timestamp(), version = version + 1
                        WHERE request_month = :month AND request_id = :id AND state = 'RUNNING'
                            AND start_deadline > clock_timestamp()
                        RETURNING owner_id
                    """).bind("month", job.month).bind("id", job.id).bind("model", instructions.model)
                        .bind("version", instructions.promptVersion).bind("prompt", instructions.promptText)
                        .map { row, _ -> row.get(0, Long::class.javaObjectType)!! }.one()
                        .flatMap { owner ->
                            db.sql("""
                                INSERT INTO outbox_event(event_id, type, aggregate_id, payload)
                                VALUES (:event, 'RecommendationStarted', :aggregate, CAST(:payload AS jsonb))
                            """).bind("event", UUID.randomUUID()).bind("aggregate", job.id.toString())
                                .bind("payload", json.writeValueAsString(mapOf("requestId" to job.id,
                                    "requestMonth" to job.month.toString(), "ownerId" to owner)))
                                .fetch().rowsUpdated().then(
                                    db.sql("""
                                        UPDATE ai_execution_slot SET phase = 'CALL_STARTED', modified_at = clock_timestamp()
                                        WHERE execution_id = :execution AND phase = 'PREPARING'
                                    """).bind("execution", job.execution).fetch().rowsUpdated(),
                                ).map { check(it == 1L); true }
                        }.switchIfEmpty(Mono.defer {
                            resolve(job.execution, "FAILED", "queue_expired", null).map { false }
                        })
                }
        }.singleOrEmpty().block() == true

    fun finish(execution: UUID, outcome: String, error: String? = null, response: ItemSelectionResponse? = null) =
        resolve(execution, outcome, error, response).block() == true

    private fun resolve(execution: UUID, outcome: String, error: String?, response: ItemSelectionResponse?): Mono<Boolean> {
        var sql = db.sql("SELECT resolve_ai_execution(:execution, :outcome, :error, CAST(:selection AS jsonb))")
            .bind("execution", execution).bind("outcome", outcome)
        sql = if (error == null) sql.bindNull("error", String::class.java) else sql.bind("error", error)
        sql = if (response == null) sql.bindNull("selection", String::class.java)
              else sql.bind("selection", json.writeValueAsString(response))
        return sql.map { row, _ -> row.get(0, Boolean::class.javaObjectType)!! }.one()
    }
}
