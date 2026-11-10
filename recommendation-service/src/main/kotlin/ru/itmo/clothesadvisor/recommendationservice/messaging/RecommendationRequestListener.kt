package ru.itmo.clothesadvisor.recommendationservice.messaging

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.recommendationservice.config.SelectionJsonConfiguration
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationRequestStore
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationRequested
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode

@Component
@Profile("kafka")
internal class RecommendationRequestListener(
    private val store: RecommendationRequestStore,
    private val rejections: DeliveryRejections,
) {
    private val mapper = SelectionJsonConfiguration.mapper().rebuild()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    @KafkaListener(topics = ["ai-selection.requests"], groupId = "recommendation-service.ai-selection")
    fun receive(record: ConsumerRecord<String, ByteArray>) {
        val command = try {
            decode(record.key(), record.value())
        } catch (failure: InvalidMessage) {
            rejections.record(record, failure.reason).block()
            return
        }
        store.acceptDelivery(command) { rejection ->
            rejections.record(record, rejection.name.lowercase())
        }.block()
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
