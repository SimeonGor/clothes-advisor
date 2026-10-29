package ru.itmo.clothesadvisor.dto.recommendation

import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal

data class ItemSelectionRequest(
    val weather: ItemSelectionWeather,
    val candidates: List<ItemSelectionCandidate>,
    val occasion: String? = null,
    val preferredStyle: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val requiredItemIds: List<Long?>? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val stylePreferences: ItemSelectionStylePreferences? = null,
)

data class ItemSelectionStylePreferences(
    val preferredColorsAndMaterials: String,
    val avoidances: String,
    val additionalWishes: String,
)

data class ItemSelectionWeather(
    val temperatureC: BigDecimal,
    val windSpeedMps: BigDecimal,
    val precipitationCode: String,
    val precipitationName: String,
)

data class ItemSelectionCandidate(
    val itemId: Long,
    val name: String,
    val categoryCode: String,
    val categoryName: String,
    val color: String,
    val material: String,
    val contentType: String,
    @get:tools.jackson.databind.annotation.JsonSerialize(using = SelectionImageSerializer::class)
    val imageBase64: SelectionImage,
)

// The outfit consumer still accepts legacy responses; capsule consumers must require explanation.
data class ItemSelectionResponse(val itemIds: List<Long>, val explanation: String? = null)
