package ru.itmo.clothesadvisor.accountservice.messaging

import java.util.UUID
import java.util.concurrent.ExecutionException
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled

internal class AccessOutboxRelay(
    private val database: DatabaseClient,
    private val producer: Producer<String, String>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private data class Delivery(val id: UUID, val aggregate: String, val envelope: String, val attempt: Int)

    @Scheduled(fixedDelay = 1000)
    fun publishPending() {
        repeat(32) {
            val delivery = claim() ?: return
            try {
                producer.send(ProducerRecord("stylist-access.events", "access/${delivery.aggregate}", delivery.envelope)).get()
            } catch (failure: ExecutionException) {
                retry(delivery, failure)
                return
            } catch (failure: KafkaException) {
                retry(delivery, failure)
                return
            }
            database.sql("UPDATE outbox_event SET published_at = now() WHERE event_id = :id AND published_at IS NULL")
                .bind("id", delivery.id).fetch().rowsUpdated().block()
        }
    }

    // Claim one committed row before contacting Kafka. A crashed relay leaves a retryable lease.
    private fun claim(): Delivery? = database.sql(
        """
        WITH pending AS (
            SELECT event_id FROM outbox_event
            WHERE published_at IS NULL AND next_attempt_at <= now()
            ORDER BY next_attempt_at, created_at, event_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
        )
        UPDATE outbox_event e SET attempts = attempts + 1, next_attempt_at = now() + interval '5 minutes'
        FROM pending WHERE e.event_id = pending.event_id
        RETURNING e.event_id, aggregate_id, attempts,
            jsonb_build_object('eventId', e.event_id, 'eventType', type, 'schemaVersion', schema_version,
                'occurredAt', to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'aggregateId', aggregate_id, 'payload', payload)::text AS envelope
        """,
    ).map { row ->
        Delivery(
            requireNotNull(row.get("event_id", UUID::class.java)),
            requireNotNull(row.get("aggregate_id", String::class.java)),
            requireNotNull(row.get("envelope", String::class.java)),
            requireNotNull(row.get("attempts", Int::class.javaObjectType)),
        )
    }.one().block()

    private fun retry(delivery: Delivery, failure: Exception) {
        logger.warn("Access event {} publish failed ({})", delivery.id, (failure.cause ?: failure).javaClass.simpleName)
        database.sql(
            """UPDATE outbox_event SET next_attempt_at = now() + interval '5 seconds'
               WHERE event_id = :id AND attempts = :attempt AND published_at IS NULL""",
        ).bind("id", delivery.id).bind("attempt", delivery.attempt).fetch().rowsUpdated().block()
    }
}
