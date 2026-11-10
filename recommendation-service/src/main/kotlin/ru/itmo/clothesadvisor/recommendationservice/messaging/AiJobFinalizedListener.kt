package ru.itmo.clothesadvisor.recommendationservice.messaging

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@Component
@Profile("kafka")
internal class AiJobFinalizedListener(private val database: DatabaseClient) {
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    @KafkaListener(topics = ["ai-jobs.events"], groupId = "recommendation-service.ai-jobs")
    fun receive(record: ConsumerRecord<String, ByteArray>) {
        val ack = try {
            decode(record)
        } catch (_: InvalidAcknowledgement) {
            reject(record, "invalid_finalization")
            return
        }
        val identity = "request_month=:month AND request_id=:id AND owner_id=:owner"
        val compatible = "(state=:outcome OR (state='SUCCEEDED' AND :outcome='FAILED'))"
        fun DatabaseClient.GenericExecuteSpec.bindAck() = bind("month", ack.month).bind("id", ack.id)
            .bind("owner", ack.owner).bind("outcome", ack.outcome)
        val updated = database.sql(
            """UPDATE recommendation_request SET applied_at=COALESCE(applied_at,clock_timestamp()),
               application_outcome=:outcome,version=version+1,modified_at=clock_timestamp()
               WHERE $identity AND $compatible AND application_outcome IS NULL""",
        ).bindAck().fetch().rowsUpdated().block()
        if (updated == 0L) {
            val alreadyApplied = database.sql(
                """SELECT EXISTS(SELECT FROM recommendation_request WHERE $identity AND $compatible
                   AND applied_at IS NOT NULL AND application_outcome=:outcome)""",
            ).bindAck().map { row, _ -> row.get(0, Boolean::class.javaObjectType)!! }.one().block()
            if (alreadyApplied != true) reject(record, "finalization_conflict")
        }
    }

    private fun decode(record: ConsumerRecord<String, ByteArray>): Acknowledgement {
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
            return Acknowledgement(id, month, owner.longValue(), outcome)
        } catch (_: JacksonException) {
            invalid()
        } catch (_: DateTimeParseException) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private fun reject(record: ConsumerRecord<*, *>, reason: String) {
        database.sql(
            """INSERT INTO kafka_delivery_rejection (topic,partition_id,record_offset,reason)
               VALUES (:topic,:partition,:offset,:reason) ON CONFLICT DO NOTHING""",
        ).bind("topic", record.topic()).bind("partition", record.partition()).bind("offset", record.offset())
            .bind("reason", reason).fetch().rowsUpdated().block()
    }

    private data class Acknowledgement(val id: UUID, val month: LocalDate, val owner: Long, val outcome: String)
    private class InvalidAcknowledgement : RuntimeException()
}
