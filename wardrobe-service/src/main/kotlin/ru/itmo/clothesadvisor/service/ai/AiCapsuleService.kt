package ru.itmo.clothesadvisor.service.ai

import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.capsule.CapsuleResponse
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_AI_CAPSULE
import ru.itmo.clothesadvisor.service.capsule.CapsuleService
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.core.type.TypeReference

@Service
internal class AiCapsuleService(
    private val selections: AiSelectionService,
    private val capsules: CapsuleService,
    private val idempotency: IdempotencyService,
) {
    fun create(ownerId: Long, request: CreateAiCapsuleRequest, key: String? = null): CapsuleResponse {
        selections.requireAccess(ownerId)
        return idempotency.execute(
            ownerId, CREATE_AI_CAPSULE, key, listOf(ownerId, request),
            object : TypeReference<CapsuleResponse>() {},
        ) { completion ->
            selections.select(ownerId, completion, goal = AiSelectionGoal.CAPSULE, prepare = {
                request.criteria()
            }) { _, response ->
                val explanation = response.explanation
                if (explanation.isNullOrBlank() || explanation.length > 2000) {
                    throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
                }
                capsules.createAi(ownerId, request, response.itemIds, explanation)
            }
        }
    }
}
