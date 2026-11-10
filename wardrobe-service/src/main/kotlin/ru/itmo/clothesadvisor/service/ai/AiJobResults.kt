package ru.itmo.clothesadvisor.service.ai

import java.security.MessageDigest
import java.util.UUID
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.ai.AiJobType
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.messaging.AiExecutionEvent
import ru.itmo.clothesadvisor.messaging.InvalidExecutionEvent
import ru.itmo.clothesadvisor.service.capsule.CapsuleService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import tools.jackson.databind.json.JsonMapper

@Service
internal class AiJobResults(
    private val jdbc: JdbcTemplate, private val validation: AiJobValidation,
    private val outfits: OutfitService, private val capsules: CapsuleService,
    private val mapper: JsonMapper, manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    fun receive(record: ConsumerRecord<String, ByteArray>) {
        val event = try {
            AiExecutionEvent.decode(record)
        } catch (invalid: InvalidExecutionEvent) {
            reject(record, invalid.reason)
            return
        }
        val authorized = if (event.outcome == "SUCCEEDED" && jdbc.queryForObject(
                """SELECT EXISTS (SELECT FROM ai_job WHERE job_id=? AND request_month=? AND owner_id=?
                   AND status IN ('QUEUED','RUNNING'))""",
                Boolean::class.java, event.id, event.month, event.owner,
            ) == true
        ) validation.authorized(event.owner) else null

        transaction.executeWithoutResult {
            val job = validation.lock(event.id)
            if (job == null || job.ownerId != event.owner || job.month != event.month) {
                reject(record, "job_mismatch")
                return@executeWithoutResult
            }
            if (event.outcome == null) {
                jdbc.update("""UPDATE ai_job SET status='RUNNING',version=version+1,modified_at=clock_timestamp()
                    WHERE job_id=? AND status='QUEUED'""", event.id)
                return@executeWithoutResult
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(
                listOf(event.outcome, event.items, event.explanation, event.errorCode),
            ))
            if (!job.active) {
                val prior = jdbc.queryForObject("SELECT provider_result_digest FROM ai_job WHERE job_id=?", ByteArray::class.java, event.id)
                if (prior != null && prior.contentEquals(digest)) finalized(event.id)
                else reject(record, "terminal_result_conflict")
                return@executeWithoutResult
            }

            val applied = when (event.outcome) {
                "FAILED", "UNKNOWN" -> Application(event.outcome, error = event.errorCode)
                else -> success(job, event, checkNotNull(authorized))
            }
            if (applied.error == "invalid_ai_response" && event.outcome == "SUCCEEDED") reject(record, "invalid_ai_response")
            jdbc.update(
                """UPDATE ai_job SET input=NULL, status=?,result_type=?,result_id=?,error_code=?,
                   provider_result_digest=?,version=version+1,modified_at=clock_timestamp() WHERE job_id=?""",
                applied.status, applied.type, applied.id, applied.error, digest, event.id,
            )
            finalized(event.id)
        }
    }

    private fun success(job: LockedAiJob, event: AiExecutionEvent, authorized: Boolean): Application {
        if (!authorized) return Application("FAILED", error = "access_revoked")
        val input = requireNotNull(job.input)
        val selection = input.selection
        val ids = event.items
        val explanation = event.explanation
        if (ids.isEmpty() || ids.toSet().size != ids.size ||
            !selection.candidates.map { it.itemId }.containsAll(ids) || !ids.containsAll(selection.requiredItemIds) ||
            (job.type == AiJobType.OUTFIT_REPLACEMENT && ids.all { it in selection.requiredItemIds }) ||
            explanation.isNullOrBlank() || explanation.length > 2000 || '\u0000' in explanation
        ) return Application("FAILED", error = "invalid_ai_response")
        if (!validation.currentInput(job) || jdbc.query(
                "SELECT id FROM precipitation_type WHERE id=? FOR SHARE", { row, _ -> row.getLong(1) },
                input.precipitationTypeId,
            ).isEmpty()
        ) return Application("FAILED", error = "source_changed")
        val weather = OutfitWeatherDto(selection.weather.temperatureC, input.precipitationTypeId, selection.weather.windSpeedMps)
        return if (job.type == AiJobType.CAPSULE_SELECTION) {
            val request = CreateAiCapsuleRequest(input.name, weather, occasion = selection.occasion, preferredStyle = selection.preferredStyle)
            Application("SUCCEEDED", "CAPSULE", capsules.createAi(job.ownerId, request, ids, explanation).id)
        } else {
            val outfit = outfits.createAi(job.ownerId, CreateOutfitRequest(input.name, ids, weather), selection.occasion,
                selection.preferredStyle, explanation, input.sourceOutfitId)
            Application("SUCCEEDED", "OUTFIT", outfit.id)
        }
    }

    // Rebuild the same readiness event after relay retention, even if the outfit was deleted.
    private fun finalized(id: UUID) {
        val eventId = UUID.nameUUIDFromBytes("AiJobFinalized/$id".toByteArray(Charsets.UTF_8))
        jdbc.update(
            """INSERT INTO outbox_event (event_id,type,schema_version,aggregate_id,payload,created_at)
               SELECT ?, 'AiJobFinalized',1,job_id::text,
                 jsonb_build_object('requestId',job_id,'requestMonth',request_month,'ownerId',owner_id,
                                    'applicationOutcome',status), modified_at
               FROM ai_job WHERE job_id=?
               ON CONFLICT(event_id) DO UPDATE SET published_at=NULL, next_attempt_at=clock_timestamp()
               WHERE outbox_event.published_at IS NOT NULL""", eventId, id,
        )
    }

    private fun reject(record: ConsumerRecord<*, *>, reason: String) {
        jdbc.update("""INSERT INTO kafka_delivery_rejection (topic,partition_id,record_offset,reason)
            VALUES (?,?,?,?) ON CONFLICT DO NOTHING""", record.topic(), record.partition(), record.offset(), reason)
    }

    private data class Application(val status: String, val type: String? = null, val id: Long? = null, val error: String? = null)
}
