package ru.itmo.clothesadvisor.dto.ai

import java.math.BigDecimal

data class OutfitSelectionRequest(
    val weather: OutfitSelectionWeather,
    val candidates: List<OutfitSelectionCandidate>,
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

data class OutfitSelectionResponse(val itemIds: List<Long>)
