package ru.itmo.clothesadvisor.recommendationservice.messaging

import io.micrometer.tracing.Span
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.observability.DurableTrace
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationFinalization
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationRequestStore
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@Component
@Profile("kafka")
internal class AiJobFinalizedListener(
    private val store: RecommendationRequestStore, private val rejections: DeliveryRejections,
    private val trace: DurableTrace = DurableTrace.disabled,
) {
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    @KafkaListener(topics = ["ai-jobs.events"], groupId = "recommendation-service.ai-jobs")
    fun receive(record: ConsumerRecord<String, ByteArray>) {
        val ack = try {
            decode(record)
        } catch (_: InvalidAcknowledgement) {
            rejections.record(record, "invalid_finalization").block()
            return
        }
        val parent = DurableTrace.header(record.headers().lastHeader(DurableTrace.HEADER)?.value())
        trace.child(parent, "ai-jobs.events acknowledge", Span.Kind.CONSUMER) {
            if (store.applyFinalization(ack).block() != true) rejections.record(record, "finalization_conflict").block()
        }
    }

    private fun decode(record: ConsumerRecord<String, ByteArray>): RecommendationFinalization {
        fun invalid(): Nothing = throw InvalidAcknowledgement()
        fun JsonNode.text(name: String) = path(name).takeIf { it.isString }?.stringValue() ?: invalid()
        fun uuid(text: String) = UUID.fromString(text).also { if (it.toString() != text) invalid() }
        try {
            val event = mapper.readTree(record.value() ?: invalid())
            val version = event.path("schemaVersion")
            if (!version.isIntegralNumber || version.asString() != "1" || event.text("eventType") != "AiJobFinalized") invalid()
            uuid(event.text("eventId"))
            val occurred = event.text("occurredAt")
            if (!occurred.endsWith("Z")) invalid()
            Instant.parse(occurred)
            val payload = event.path("payload")
            val id = uuid(payload.text("requestId"))
            val period = payload.text("requestMonth")
            if (period.length != 10) invalid()
            val month = LocalDate.parse(period)
            if (month.dayOfMonth != 1 || record.key() != "$month/$id" || event.text("aggregateId") != id.toString()) invalid()
            val owner = payload.path("ownerId")
            if (!owner.isIntegralNumber || !owner.canConvertToLong() || owner.longValue() <= 0) invalid()
            val outcome = payload.text("applicationOutcome")
            if (outcome !in setOf("SUCCEEDED", "FAILED", "UNKNOWN")) invalid()
            return RecommendationFinalization(id, month, owner.longValue(), outcome)
        } catch (_: JacksonException) {
            invalid()
        } catch (_: DateTimeParseException) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private class InvalidAcknowledgement : RuntimeException()
}
