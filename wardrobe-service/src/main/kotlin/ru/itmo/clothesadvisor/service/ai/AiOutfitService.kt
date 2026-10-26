package ru.itmo.clothesadvisor.service.ai

import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_AI_OUTFIT
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_AI_OUTFIT_REPLACEMENT
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import tools.jackson.core.type.TypeReference

@Service
internal class AiOutfitService(
    private val selections: AiSelectionService,
    private val outfits: OutfitService,
    private val idempotency: IdempotencyService,
) {
    fun create(ownerId: Long, request: CreateAiOutfitRequest, key: String? = null): OutfitResponse {
        selections.requireAccess(ownerId)
        return idempotency.execute(
            ownerId, CREATE_AI_OUTFIT, key, listOf(ownerId, request),
            object : TypeReference<OutfitResponse>() {},
        ) { completion ->
            selections.select(ownerId, completion, prepare = {
                AiSelectionCriteria(
                    request.weather,
                    request.candidateItemIds,
                    request.occasion,
                    request.preferredStyle,
                )
            }) { criteria, response ->
                outfits.createAi(
                    ownerId,
                    CreateOutfitRequest(request.name, response.itemIds, criteria.weather),
                    criteria.occasion,
                    criteria.preferredStyle,
                    response.explanation,
                )
            }
        }
    }

    fun replace(
        ownerId: Long,
        sourceId: Long,
        request: CreateAiOutfitReplacementRequest,
        key: String? = null,
    ): OutfitResponse {
        selections.requireAccess(ownerId)
        return idempotency.execute(
            ownerId, CREATE_AI_OUTFIT_REPLACEMENT, key, listOf(ownerId, sourceId, request),
            object : TypeReference<OutfitResponse>() {},
        ) { completion ->
            selections.select(ownerId, completion, prepare = {
                val source = outfits.get(ownerId, sourceId)
                validateIds(request.replaceItemIds)
                val replaced = request.replaceItemIds.map { requireNotNull(it) }.toSet()
                if (!source.itemIds.containsAll(replaced)) {
                    throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
                }
                AiSelectionCriteria(
                    source.weather,
                    request.candidateItemIds,
                    source.occasion,
                    source.preferredStyle,
                    source.itemIds.filter { it !in replaced }.toSet(),
                    replaced,
                )
            }) { criteria, response ->
                outfits.createAi(
                    ownerId,
                    CreateOutfitRequest(request.name, response.itemIds, criteria.weather),
                    criteria.occasion,
                    criteria.preferredStyle,
                    response.explanation,
                    sourceId,
                )
            }
        }
    }

    private fun validateIds(ids: List<Long?>) {
        if (ids.isEmpty() || ids.any { it == null || it <= 0 } || ids.distinct().size != ids.size) {
            throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
        }
    }
}
