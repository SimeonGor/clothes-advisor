package ru.itmo.clothesadvisor.recommendationservice.messaging

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile("kafka")
class ExecutionOutboxRelay(private val database: DatabaseClient, private val kafka: KafkaTemplate<String, String>) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 250)
    fun publishNext() {
        val event = database.sql(CLAIM).map { row, _ ->
            Event(
                row.get("event_id", UUID::class.java)!!, row.get("type", String::class.java)!!,
                row.get("aggregate_id", String::class.java)!!, row.get("attempts", Int::class.javaObjectType)!!,
                row.get("request_month", String::class.java), row.get("envelope", String::class.java)!!,
            )
        }.one().block() ?: return
        try {
            check(event.type == "RecommendationStarted" || event.type == "RecommendationFinished")
            val key = "${requireNotNull(event.month)}/${event.aggregateId}"
            kafka.send("ai-selection.execution-events", key, event.envelope).get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        } catch (_: Exception) {
            logger.warn("Execution outbox send failed for event {}", event.id)
            return
        }
        database.sql(
            """
            UPDATE outbox_event SET published_at = clock_timestamp()
            WHERE event_id = :id AND attempts = :attempts AND published_at IS NULL
            """,
        ).bind("id", event.id).bind("attempts", event.attempts).fetch().rowsUpdated().block()
    }

    private data class Event(
        val id: UUID, val type: String, val aggregateId: String, val attempts: Int, val month: String?, val envelope: String,
    )

    private companion object {
        const val CLAIM = """
            WITH due AS (
                SELECT event_id FROM outbox_event
                WHERE published_at IS NULL AND next_attempt_at <= clock_timestamp()
                ORDER BY next_attempt_at, created_at, event_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_event AS e
            SET attempts = attempts + 1, next_attempt_at = clock_timestamp() + interval '60 seconds'
            FROM due WHERE e.event_id = due.event_id
            RETURNING e.event_id, e.type, e.aggregate_id, e.attempts, e.payload->>'requestMonth' AS request_month,
                jsonb_build_object(
                    'eventId', e.event_id, 'eventType', e.type, 'schemaVersion', e.schema_version,
                    'aggregateId', e.aggregate_id,
                    'occurredAt', to_char(e.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                    'payload', e.payload
                )::text AS envelope
        """
    }
}
