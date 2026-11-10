package ru.itmo.clothesadvisor.notification

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal const val ACCESS = "stylist-access.events"
internal const val REQUESTS = "stylist-requests.events"
internal const val OUTFITS = "stylist-outfits.events"
internal const val RATINGS = "outfit-ratings.events"
internal const val JOBS = "ai-jobs.events"

// R2DBC maps earlier instants to -infinity and rounds to the nearest microsecond.
private val firstFiniteTimestamp = Instant.parse("-4712-01-01T00:00:00Z")
private val firstTimestampRoundingPastPostgresRange = Instant.parse("+294277-01-01T00:00:00Z").minusNanos(500)

internal enum class RejectionReason { INVALID_EVENT, UNSUPPORTED_VERSION }

internal class InvalidDomainEvent(val reason: RejectionReason = RejectionReason.INVALID_EVENT) : RuntimeException()

@Component
internal class DomainEvents {
    private val json = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun notification(topic: String, body: ByteArray?): NewNotification {
        try {
            val event = json.readTree(body ?: throw InvalidDomainEvent())
            if (event.id("schemaVersion") != 1L) throw InvalidDomainEvent(RejectionReason.UNSUPPORTED_VERSION)
            val eventId = uuid(event.text("eventId"))
            val type = event.text("eventType")
            event.text("aggregateId")
            val occurredAt = Instant.parse(event.text("occurredAt"))
            if (occurredAt < firstFiniteTimestamp || occurredAt >= firstTimestampRoundingPastPostgresRange) {
                throw InvalidDomainEvent()
            }
            val payload = event.path("payload")
            val owner = payload.id("ownerId")
            fun build(recipient: Long, message: String, resource: NotificationResource?) =
                NewNotification(eventId, recipient, type, message, resource, occurredAt)

            return when (topic) {
                ACCESS -> {
                    val stylist = payload.id("stylistId")
                    when (type) {
                        "AccessGranted" -> build(stylist, "Вам предоставили доступ к гардеробу.",
                            NotificationResource("CLIENT", owner.toString()))
                        "AccessRevoked" -> build(stylist, "Доступ к гардеробу отозван.", null)
                        else -> throw InvalidDomainEvent()
                    }
                }
                REQUESTS -> {
                    val requestId = payload.id("requestId")
                    when (type) {
                        "StylistRequestCreated", "StylistRequestCancelled" -> {
                            val message = if (type == "StylistRequestCreated") "Получен запрос на подбор образа."
                                else "Запрос на подбор образа отменён."
                            build(payload.id("stylistId"), message, NotificationResource("CLIENT_REQUEST", "$owner/$requestId"))
                        }
                        "StylistRequestAnswered" -> build(owner, "Стилист ответил на ваш запрос.",
                            NotificationResource("STYLIST_REQUEST", requestId.toString()))
                        else -> throw InvalidDomainEvent()
                    }
                }
                OUTFITS, RATINGS -> {
                    val message = when (topic to type) {
                        OUTFITS to "StylistOutfitCreated" -> "Стилист добавил образ."
                        RATINGS to "OutfitRatingChanged" -> "У вашего образа изменилась оценка."
                        RATINGS to "OutfitRatingWithdrawn" -> "Оценка вашего образа отозвана."
                        else -> throw InvalidDomainEvent()
                    }
                    build(owner, message, NotificationResource("OUTFIT", payload.id("outfitId").toString()))
                }
                JOBS -> {
                    if (type != "AiJobFinalized" || LocalDate.parse(payload.text("requestMonth")).dayOfMonth != 1) {
                        throw InvalidDomainEvent()
                    }
                    val message = when (payload.text("applicationOutcome")) {
                        "SUCCEEDED" -> "AI-подбор завершён."
                        "FAILED" -> "AI-подбор не удался."
                        "UNKNOWN" -> "Результат AI-подбора неизвестен."
                        else -> throw InvalidDomainEvent()
                    }
                    build(owner, message, NotificationResource("AI_JOB", uuid(payload.text("requestId")).toString()))
                }
                else -> throw InvalidDomainEvent()
            }
        } catch (_: JacksonException) {
            throw InvalidDomainEvent()
        } catch (_: DateTimeParseException) {
            throw InvalidDomainEvent()
        }
    }

    private fun JsonNode.text(key: String): String = path(key).takeIf { it.isString }?.stringValue()
        ?.takeIf { it.isNotBlank() } ?: throw InvalidDomainEvent()

    private fun JsonNode.id(key: String): Long = path(key)
        .takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        ?.takeIf { it > 0 } ?: throw InvalidDomainEvent()

    private fun uuid(value: String): UUID {
        val id = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { throw InvalidDomainEvent() }
        if (!id.toString().equals(value, ignoreCase = true)) throw InvalidDomainEvent()
        return id
    }
}
