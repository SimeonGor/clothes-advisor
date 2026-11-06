package ru.itmo.clothesadvisor.service.ai

import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto

internal data class AiSelectionCriteria(
    val weather: OutfitWeatherDto,
    val candidateItemIds: List<Long?>? = null,
    val occasion: String? = null,
    val preferredStyle: String? = null,
    val requiredItemIds: Set<Long>? = null,
    val excludedIds: Set<Long> = emptySet(),
)
