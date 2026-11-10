package ru.itmo.clothesadvisor.recommendationservice.messaging

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.recommendationservice.config.SelectionJsonConfiguration
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationRequestStore
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationRequested
import ru.itmo.clothesadvisor.recommendationservice.request.RequestAcceptance
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode

@Component
@Profile("kafka")
class RecommendationRequestListener(
    private val store: RecommendationRequestStore,
    private val database: DatabaseClient,
    transactionManager: ReactiveTransactionManager,
) {
    private val transaction = TransactionalOperator.create(transactionManager)
    private val mapper = SelectionJsonConfiguration.mapper().rebuild()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    @KafkaListener(topics = ["ai-selection.requests"], groupId = "recommendation-service.ai-selection")
    fun receive(record: ConsumerRecord<String, ByteArray>) {
        val command = try {
            decode(record.key(), record.value())
        } catch (failure: InvalidMessage) {
            recordRejection(record, failure.reason).block()
            return
        }
        // The retention lock acquired by accept also protects reconstruction of a terminal event.
        store.accept(command).flatMap { decision ->
            when (decision) {
                RequestAcceptance.ACCEPTED -> Mono.empty()
                RequestAcceptance.DUPLICATE -> requestReplay(command)
                else -> recordRejection(record, decision.name.lowercase())
            }
        }.let(transaction::transactional).block()
    }

    private fun decode(key: String?, bytes: ByteArray?): RecommendationRequested {
        if (bytes == null) throw InvalidMessage("invalid_envelope")
        val envelope = try {
            mapper.readValue(bytes, Envelope::class.java) ?: throw InvalidMessage("invalid_envelope")
        } catch (_: JacksonException) {
            throw InvalidMessage("invalid_envelope")
        }
        if (envelope.eventType != "RecommendationRequested") throw InvalidMessage("unknown_event_type")
        if (envelope.schemaVersion != 1) throw InvalidMessage("unsupported_schema_version")
        try {
            if (UUID.fromString(envelope.eventId).toString() != envelope.eventId.lowercase() ||
                !envelope.occurredAt.endsWith("Z")
            ) throw InvalidMessage("invalid_envelope")
            Instant.parse(envelope.occurredAt)
        } catch (_: IllegalArgumentException) {
            throw InvalidMessage("invalid_envelope")
        } catch (_: DateTimeParseException) {
            throw InvalidMessage("invalid_envelope")
        }
        val payload = envelope.payload
        val version = payload.path("inputVersion")
        if (version.isIntegralNumber && version.asString() != "1") throw InvalidMessage("unsupported_input_version")
        for (name in listOf("requestId", "requestMonth", "acceptedAt", "startDeadline")) {
            if (!payload.path(name).isString) throw InvalidMessage("invalid_input")
        }
        val command = try {
            mapper.treeToValue(payload, RecommendationRequested::class.java)
        } catch (_: JacksonException) {
            throw InvalidMessage("invalid_input")
        }
        if (envelope.aggregateId != command.requestId.toString() ||
            payload.path("requestId").asString() != envelope.aggregateId ||
            key != "${command.requestMonth}/${command.requestId}"
        ) throw InvalidMessage("request_key_mismatch")
        return command
    }

    private fun recordRejection(record: ConsumerRecord<*, *>, reason: String): Mono<Void> =
        database.sql(
            """
            INSERT INTO kafka_delivery_rejection (topic, partition_id, record_offset, reason)
            VALUES (:topic, :partition, :offset, :reason) ON CONFLICT DO NOTHING
            """,
        ).bind("topic", record.topic()).bind("partition", record.partition()).bind("offset", record.offset())
            .bind("reason", reason).fetch().rowsUpdated().then()

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

    private data class Envelope(
        val eventId: String,
        val eventType: String,
        val schemaVersion: Int,
        val occurredAt: String,
        val aggregateId: String,
        val payload: JsonNode,
    )

    private class InvalidMessage(val reason: String) : RuntimeException()
}
