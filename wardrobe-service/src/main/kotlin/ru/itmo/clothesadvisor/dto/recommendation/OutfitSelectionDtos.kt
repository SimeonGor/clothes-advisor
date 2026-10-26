package ru.itmo.clothesadvisor.dto.recommendation

import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal

data class OutfitSelectionRequest(
    val weather: OutfitSelectionWeather,
    val candidates: List<OutfitSelectionCandidate>,
    val occasion: String? = null,
    val preferredStyle: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val requiredItemIds: List<Long?>? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val stylePreferences: OutfitSelectionStylePreferences? = null,
)

data class OutfitSelectionStylePreferences(
    val preferredColorsAndMaterials: String,
    val avoidances: String,
    val additionalWishes: String,
)

data class OutfitSelectionWeather(
    val temperatureC: BigDecimal,
    val windSpeedMps: BigDecimal,
    val precipitationCode: String,
    val precipitationName: String,
)

data class OutfitSelectionCandidate(
    val itemId: Long,
    val name: String,
    val categoryCode: String,
    val categoryName: String,
    val color: String,
    val material: String,
    val contentType: String,
    val imageBase64: String,
)

data class OutfitSelectionResponse(val itemIds: List<Long>, val explanation: String? = null)
