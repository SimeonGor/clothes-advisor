package ru.itmo.clothesadvisor.service.ai

import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure

internal data class AiSelectionCriteria(
    val weather: OutfitWeatherDto,
    val candidateItemIds: List<Long?>? = null,
    val occasion: String? = null,
    val preferredStyle: String? = null,
    val requiredItemIds: Set<Long>? = null,
    val excludedIds: Set<Long> = emptySet(),
)

internal fun CreateAiOutfitRequest.criteria() = AiSelectionCriteria(weather, candidateItemIds, occasion, preferredStyle)

internal fun CreateAiCapsuleRequest.criteria() = AiSelectionCriteria(weather, candidateItemIds, occasion, preferredStyle)

internal fun CreateAiOutfitReplacementRequest.criteria(source: OutfitResponse): AiSelectionCriteria {
    if (replaceItemIds.isEmpty() || replaceItemIds.any { it == null || it <= 0 } ||
        replaceItemIds.distinct().size != replaceItemIds.size || !source.itemIds.containsAll(replaceItemIds)
    ) {
        throw AiSelectionException(AiSelectionFailure.INVALID_REQUEST)
    }
    val replaced = replaceItemIds.filterNotNull().toSet()
    return AiSelectionCriteria(
        source.weather, candidateItemIds, source.occasion, source.preferredStyle,
        source.itemIds.filter { it !in replaced }.toSet(), replaced,
    )
}
