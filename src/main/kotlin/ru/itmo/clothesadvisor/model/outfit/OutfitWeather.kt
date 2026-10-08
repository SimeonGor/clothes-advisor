package ru.itmo.clothesadvisor.model.outfit

import java.math.BigDecimal

internal class OutfitWeather(
    private val outfitId: Long,
    val temperatureC: BigDecimal,
    val precipitationTypeId: Long,
    val windSpeedMps: BigDecimal,
) {
    val id: Long get() = outfitId
}
