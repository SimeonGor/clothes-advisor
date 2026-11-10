package ru.itmo.clothesadvisor.messaging

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile("kafka")
class OutboxRelay(private val database: JdbcTemplate, private val kafka: KafkaTemplate<String, String>) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // One autocommit statement leases a row. No transaction spans broker I/O.
    @Scheduled(fixedDelay = 250)
    fun publishNext() {
        val event = database.query(CLAIM) { row, _ ->
            Event(
                row.getObject("event_id", UUID::class.java), row.getString("type"), row.getString("aggregate_id"),
                row.getInt("attempts"), row.getString("request_month"), row.getString("envelope"),
            )
        }.singleOrNull() ?: return

        try {
            val (topic, key) = when (event.type) {
                "RecommendationRequested" -> "ai-selection.requests" to event.aiKey()
                "AiJobFinalized" -> "ai-jobs.events" to event.aiKey()
                "StylistRequestCreated", "StylistRequestCancelled", "StylistRequestAnswered" ->
                    "stylist-requests.events" to "stylist-request/${event.aggregateId}"
                "StylistOutfitCreated" -> "stylist-outfits.events" to "outfit/${event.aggregateId}"
                "OutfitRatingChanged", "OutfitRatingWithdrawn" -> "outfit-ratings.events" to "outfit-rating/${event.aggregateId}"
                else -> error("Unroutable wardrobe event")
            }
            kafka.send(topic, key, event.envelope).get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        } catch (_: Exception) {
            logger.warn("Wardrobe outbox send failed for event {}", event.id)
            return // The lease expires even after a process crash.
        }
        database.update(
            "UPDATE outbox_event SET published_at = clock_timestamp() WHERE event_id = ? AND attempts = ? AND published_at IS NULL",
            event.id, event.attempts,
        )
    }

    private data class Event(
        val id: UUID, val type: String, val aggregateId: String, val attempts: Int, val month: String?, val envelope: String,
    ) {
        fun aiKey() = "${requireNotNull(month)}/$aggregateId"
    }

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
