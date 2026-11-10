package ru.itmo.clothesadvisor.repository.ai

import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.dto.ai.*
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.service.ai.AppliedAiJob
import ru.itmo.clothesadvisor.service.ai.AiJobInput
import ru.itmo.clothesadvisor.service.ai.AiSelectionGoal
import ru.itmo.clothesadvisor.service.ai.RecommendationRequested
import tools.jackson.databind.json.JsonMapper

internal class AiQueueFullException : RuntimeException()

internal data class LockedAiJob(
    val ownerId: Long, val month: LocalDate, val type: AiJobType, val status: AiJobStatus,
    val input: AiJobInput?, val expired: Boolean,
) {
    val active get() = status == AiJobStatus.QUEUED || status == AiJobStatus.RUNNING
}

@Repository
internal class AiJobRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {
    fun accept(ownerId: Long, type: AiJobType, input: AiJobInput, capacity: Int): JobAccepted {
        // All replicas serialize admission. Count in a separate READ COMMITTED statement after taking the lock.
        jdbc.execute("SELECT pg_advisory_xact_lock('ai_job'::regclass::oid::bigint)")
        val active = jdbc.queryForObject(
            "SELECT count(*) FROM ai_job WHERE status IN ('QUEUED', 'RUNNING')", Long::class.java,
        )!!
        if (active >= capacity) throw AiQueueFullException()

        val jobId = UUID.randomUUID()
        val request = jdbc.queryForObject(
            """
            INSERT INTO ai_job (job_id, owner_id, type, input, request_month, start_deadline, created_at, modified_at)
            SELECT ?, ?, ?, ?::jsonb, date_trunc('month', t AT TIME ZONE 'UTC')::date, t + interval '15 minutes', t, t
            FROM (SELECT statement_timestamp() AS t) acceptance
            RETURNING request_month, created_at, start_deadline
            """,
            { row, _ ->
                RecommendationRequested(
                    jobId, row.getObject("request_month", LocalDate::class.java), ownerId,
                    row.getTimestamp("created_at").toInstant(), row.getTimestamp("start_deadline").toInstant(),
                    if (type == AiJobType.CAPSULE_SELECTION) AiSelectionGoal.CAPSULE else AiSelectionGoal.OUTFIT,
                    input.selection,
                )
            }, jobId, ownerId, type.name, mapper.writeValueAsString(input),
        )
        jdbc.update(
            """
            INSERT INTO outbox_event (event_id, type, schema_version, aggregate_id, payload, created_at)
            VALUES (?, 'RecommendationRequested', 1, ?, ?::jsonb, ?)
            """, UUID.randomUUID(), jobId.toString(), mapper.writeValueAsString(request), java.sql.Timestamp.from(request.acceptedAt),
        )
        return JobAccepted(jobId)
    }

    fun get(ownerId: Long, jobId: UUID): AiJobResponse = jdbc.query(
        "SELECT $publicFields FROM ai_job WHERE job_id = ? AND owner_id = ?",
        { row, _ -> response(row) }, jobId, ownerId,
    ).singleOrNull() ?: throw EntityNotFoundException()

    fun list(ownerId: Long, page: Int, size: Int): List<AiJobResponse> = jdbc.query(
        "SELECT $publicFields FROM ai_job WHERE owner_id = ? ORDER BY created_at DESC, job_id DESC LIMIT ? OFFSET ?",
        { row, _ -> response(row) }, ownerId, size, page.toLong() * size,
    )

    fun count(ownerId: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE owner_id = ?", Long::class.java, ownerId)!!

    fun findOwner(id: UUID, month: LocalDate): Long? =
        jdbc.query("SELECT owner_id FROM ai_job WHERE job_id = ? AND request_month = ?",
            { row, _ -> row.getLong(1) }, id, month).singleOrNull()

    fun isActive(id: UUID, month: LocalDate, owner: Long): Boolean = jdbc.queryForObject(
        """SELECT EXISTS (SELECT FROM ai_job WHERE job_id=? AND request_month=? AND owner_id=?
           AND status IN ('QUEUED','RUNNING'))""",
        Boolean::class.java, id, month, owner,
    ) == true

    fun lock(id: UUID): LockedAiJob? = jdbc.query(
        """SELECT owner_id, request_month, type, status, input::text, start_deadline <= clock_timestamp() AS expired
           FROM ai_job WHERE job_id = ? FOR UPDATE""",
        { row, _ -> LockedAiJob(row.getLong("owner_id"), row.getObject("request_month", LocalDate::class.java),
            AiJobType.valueOf(row.getString("type")), AiJobStatus.valueOf(row.getString("status")),
            row.getString("input")?.let { mapper.readValue(it, AiJobInput::class.java) }, row.getBoolean("expired")) }, id,
    ).singleOrNull()

    // Share locks on both rows close the deletion/rebinding race until the resource commits.
    fun currentInput(job: LockedAiJob): Boolean {
        val input = requireNotNull(job.input)
        if (input.sourceOutfitId != null && jdbc.query(
                "SELECT id FROM outfit WHERE id = ? AND owner_id = ? FOR UPDATE",
                { row, _ -> row.getLong(1) }, input.sourceOutfitId, job.ownerId,
            ).isEmpty()
        ) return false
        return input.selection.candidates.sortedBy { it.itemId }.all { candidate ->
            jdbc.query(
                """SELECT i.id FROM wardrobe_item i JOIN wardrobe_item_photo p ON p.wardrobe_item_id = i.id
                   WHERE i.id = ? AND i.owner_id = ? AND i.availability = 'AVAILABLE'
                     AND p.id = ? AND p.file_id = ? AND p.content_type = ? AND p.size_bytes = ?
                   FOR SHARE OF i, p""",
                { row, _ -> row.getLong(1) }, candidate.itemId, job.ownerId, candidate.photoId,
                candidate.fileId, candidate.contentType, candidate.sizeBytes,
            ).isNotEmpty()
        }
    }

    fun lockPrecipitation(id: Long): Boolean = jdbc.query(
        "SELECT id FROM precipitation_type WHERE id=? FOR SHARE", { row, _ -> row.getLong(1) }, id,
    ).isNotEmpty()

    fun markRunning(id: UUID) {
        jdbc.update("""UPDATE ai_job SET status='RUNNING',version=version+1,modified_at=clock_timestamp()
            WHERE job_id=? AND status='QUEUED'""", id)
    }

    fun storedDigest(id: UUID): ByteArray? =
        jdbc.queryForObject("SELECT provider_result_digest FROM ai_job WHERE job_id=?", ByteArray::class.java, id)

    fun complete(id: UUID, applied: AppliedAiJob, digest: ByteArray) {
        jdbc.update(
            """UPDATE ai_job SET input=NULL, status=?,result_type=?,result_id=?,error_code=?,
               provider_result_digest=?,version=version+1,modified_at=clock_timestamp() WHERE job_id=?""",
            applied.status, applied.type, applied.id, applied.error, digest, id,
        )
    }

    // Rebuild the same readiness event after relay retention, even if the outfit was deleted.
    fun enqueueFinalization(id: UUID) {
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

    private fun response(row: ResultSet): AiJobResponse {
        val result = row.getString("result_type")?.let { type ->
            val id = row.getLong("result_id")
            AiJobResult(type, id, if (type == "CAPSULE") "/api/me/capsules/$id" else "/api/outfits/$id")
        }
        val error = row.getString("error_code")?.let { code ->
            val message = when (code) {
                "source_changed" -> "Исходные данные изменились."
                "access_revoked" -> "Доступ к подбору отозван."
                "queue_expired" -> "Истекло время ожидания подбора."
                "selection_unavailable" -> "Подбор недоступен."
                "invalid_ai_response" -> "Сервис подбора вернул некорректный ответ."
                "dependency_unavailable" -> "Один из сервисов временно недоступен."
                else -> "Не удалось подтвердить результат подбора."
            }
            AiJobError(code, message)
        }
        return AiJobResponse(
            row.getObject("job_id", UUID::class.java), AiJobType.valueOf(row.getString("type")),
            AiJobStatus.valueOf(row.getString("status")), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("modified_at").toInstant(), result, error,
        )
    }

    private val publicFields = "job_id, type, status, created_at, modified_at, result_type, result_id, error_code"
}
