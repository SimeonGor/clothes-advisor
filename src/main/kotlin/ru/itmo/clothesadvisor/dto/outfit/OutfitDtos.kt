package ru.itmo.clothesadvisor.dto.outfit

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import ru.itmo.clothesadvisor.model.outfit.OutfitSource

internal class CreateOutfitRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    val itemIds: List<Long?>,
    @field:Valid val weather: OutfitWeatherDto,
)

internal data class OutfitWeatherDto(
    val temperatureC: BigDecimal,
    val precipitationTypeId: Long,
    @field:DecimalMin("0") val windSpeedMps: BigDecimal,
)

internal data class OutfitResponse(
    val id: Long,
    val ownerId: Long,
    val authorId: Long,
    val source: OutfitSource,
    val name: String,
    val itemIds: List<Long>,
    val weather: OutfitWeatherDto,
    val createdAt: Instant,
)
