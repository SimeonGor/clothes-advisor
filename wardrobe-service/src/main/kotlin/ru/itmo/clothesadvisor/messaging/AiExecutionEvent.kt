package ru.itmo.clothesadvisor.messaging

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import org.apache.kafka.clients.consumer.ConsumerRecord
import ru.itmo.clothesadvisor.service.ai.AiExecutionEvent
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal class InvalidExecutionEvent(val reason: String) : RuntimeException()

internal object AiExecutionEventDecoder {
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    private val failures = setOf("source_changed", "access_revoked", "queue_expired", "selection_unavailable",
        "invalid_ai_response", "dependency_unavailable")

    fun decode(record: ConsumerRecord<String, ByteArray>): AiExecutionEvent {
        fun invalid(): Nothing = throw InvalidExecutionEvent("invalid_envelope")
        try {
            val event = mapper.readTree(record.value() ?: invalid())
            val version = event.path("schemaVersion")
            if (!version.isIntegralNumber || version.asString() != "1") throw InvalidExecutionEvent("unsupported_schema_version")
            fun JsonNode.text(name: String) = path(name).takeIf { it.isString }?.stringValue() ?: invalid()
            fun uuid(text: String) = UUID.fromString(text).also { if (it.toString() != text) invalid() }
            uuid(event.text("eventId"))
            val occurred = event.text("occurredAt")
            if (!occurred.endsWith("Z")) invalid()
            Instant.parse(occurred)
            val p = event.path("payload")
            val id = uuid(p.text("requestId"))
            val period = p.text("requestMonth")
            if (period.length != 10) invalid()
            val month = LocalDate.parse(period)
            if (month.dayOfMonth != 1 || event.text("aggregateId") != id.toString() || record.key() != "$month/$id") {
                throw InvalidExecutionEvent("request_key_mismatch")
            }
            val owner = p.path("ownerId").positiveId() ?: invalid()
            val outcome = when (event.text("eventType")) {
                "RecommendationStarted" -> null
                "RecommendationFinished" -> p.text("outcome")
                else -> throw InvalidExecutionEvent("unknown_event_type")
            }
            val error = p.path("errorCode").takeIf { it.isString }?.stringValue()
            val fields = setOf("requestId", "requestMonth", "ownerId") + when (outcome) {
                null -> emptySet()
                "SUCCEEDED" -> setOf("outcome", "itemIds", "explanation")
                "FAILED", "UNKNOWN" -> {
                    if (if (outcome == "FAILED") error !in failures else error != "ai_outcome_unknown") invalid()
                    setOf("outcome", "errorCode")
                }
                else -> invalid()
            }
            if (p.propertyNames().any { it !in fields }) invalid()
            val items = p.path("itemIds").takeIf { it.isArray && it.size() <= 20 }
                ?.values()?.map { it.positiveId() ?: -1L }.orEmpty()
            return AiExecutionEvent(id, month, owner, outcome, items,
                p.path("explanation").takeIf { it.isString }?.stringValue(), error)
        } catch (_: JacksonException) {
            invalid()
        } catch (_: DateTimeParseException) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private fun JsonNode.positiveId() = takeIf { it.isIntegralNumber && it.canConvertToLong() }
        ?.longValue()?.takeIf { it > 0L }
}
