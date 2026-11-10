package ru.itmo.clothesadvisor.service.ai

import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.ai.AiJobStatus
import ru.itmo.clothesadvisor.dto.ai.AiJobType
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
import tools.jackson.databind.json.JsonMapper

internal class JobInputConflict : RuntimeException()

internal data class LockedAiJob(
    val ownerId: Long, val month: LocalDate, val type: AiJobType, val status: AiJobStatus,
    val input: AiJobInput?, val expired: Boolean,
) {
    val active get() = status == AiJobStatus.QUEUED || status == AiJobStatus.RUNNING
}

@Service
internal class AiJobValidation(
    private val jdbc: JdbcTemplate, private val mapper: JsonMapper, private val selections: AiSelectionService,
    manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    fun validate(id: UUID, month: LocalDate) {
        val owner = jdbc.query("SELECT owner_id FROM ai_job WHERE job_id = ? AND request_month = ?",
            { row, _ -> row.getLong(1) }, id, month).singleOrNull() ?: throw EntityNotFoundException()
        if (!authorized(owner)) throw AccessDeniedException()
        transaction.executeWithoutResult {
            val job = lock(id) ?: throw EntityNotFoundException()
            if (job.ownerId != owner || job.month != month || !job.active || job.expired || !currentInput(job)) {
                throw JobInputConflict()
            }
        }
    }

    // Account calls run before any local transaction. Transport failures propagate for retry.
    fun authorized(owner: Long): Boolean = try {
        selections.requireAccess(owner)
        true
    } catch (_: AccessDeniedException) {
        false
    } catch (error: AiSelectionException) {
        // requireAccess reports UNAVAILABLE when this login no longer owns the personal connection.
        if (error.failure != AiSelectionFailure.UNAVAILABLE) throw error
        false
    }

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
}
