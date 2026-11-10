package ru.itmo.clothesadvisor.service.ai

import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.ai.AiJobType
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.repository.ai.AiJobRepository
import ru.itmo.clothesadvisor.repository.ai.LockedAiJob
import ru.itmo.clothesadvisor.service.capsule.CapsuleService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import tools.jackson.databind.json.JsonMapper

internal data class AiExecutionEvent(
    val id: UUID, val month: LocalDate, val owner: Long,
    val outcome: String?, val items: List<Long>, val explanation: String?, val errorCode: String?,
)

internal data class AppliedAiJob(val status: String, val type: String? = null, val id: Long? = null, val error: String? = null)

@Service
internal class AiJobResults(
    private val jobs: AiJobRepository, private val validation: AiJobValidation,
    private val outfits: OutfitService, private val capsules: CapsuleService,
    private val mapper: JsonMapper, manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    fun apply(event: AiExecutionEvent, onRejected: (String) -> Unit) {
        val authorized = if (event.outcome == "SUCCEEDED" && jobs.isActive(event.id, event.month, event.owner)) {
            validation.authorized(event.owner)
        } else null

        transaction.executeWithoutResult {
            val job = jobs.lock(event.id)
            if (job == null || job.ownerId != event.owner || job.month != event.month) {
                onRejected("job_mismatch")
                return@executeWithoutResult
            }
            if (event.outcome == null) {
                jobs.markRunning(event.id)
                return@executeWithoutResult
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(
                listOf(event.outcome, event.items, event.explanation, event.errorCode),
            ))
            if (!job.active) {
                val prior = jobs.storedDigest(event.id)
                if (prior != null && prior.contentEquals(digest)) jobs.enqueueFinalization(event.id)
                else onRejected("terminal_result_conflict")
                return@executeWithoutResult
            }

            val applied = when (event.outcome) {
                "FAILED", "UNKNOWN" -> AppliedAiJob(event.outcome, error = event.errorCode)
                else -> success(job, event, checkNotNull(authorized))
            }
            if (applied.error == "invalid_ai_response" && event.outcome == "SUCCEEDED") onRejected("invalid_ai_response")
            jobs.complete(event.id, applied, digest)
            jobs.enqueueFinalization(event.id)
        }
    }

    private fun success(job: LockedAiJob, event: AiExecutionEvent, authorized: Boolean): AppliedAiJob {
        if (!authorized) return AppliedAiJob("FAILED", error = "access_revoked")
        val input = requireNotNull(job.input)
        val selection = input.selection
        val ids = event.items
        val explanation = event.explanation
        if (ids.isEmpty() || ids.toSet().size != ids.size ||
            !selection.candidates.map { it.itemId }.containsAll(ids) || !ids.containsAll(selection.requiredItemIds) ||
            (job.type == AiJobType.OUTFIT_REPLACEMENT && ids.all { it in selection.requiredItemIds }) ||
            explanation.isNullOrBlank() || explanation.length > 2000 || '\u0000' in explanation
        ) return AppliedAiJob("FAILED", error = "invalid_ai_response")
        if (!jobs.currentInput(job) || !jobs.lockPrecipitation(input.precipitationTypeId)) return AppliedAiJob("FAILED", error = "source_changed")
        val weather = OutfitWeatherDto(selection.weather.temperatureC, input.precipitationTypeId, selection.weather.windSpeedMps)
        return if (job.type == AiJobType.CAPSULE_SELECTION) {
            val request = CreateAiCapsuleRequest(input.name, weather, occasion = selection.occasion, preferredStyle = selection.preferredStyle)
            AppliedAiJob("SUCCEEDED", "CAPSULE", capsules.createAi(job.ownerId, request, ids, explanation).id)
        } else {
            val outfit = outfits.createAi(job.ownerId, CreateOutfitRequest(input.name, ids, weather), selection.occasion,
                selection.preferredStyle, explanation, input.sourceOutfitId)
            AppliedAiJob("SUCCEEDED", "OUTFIT", outfit.id)
        }
    }
}
