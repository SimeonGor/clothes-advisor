package ru.itmo.clothesadvisor.dto.stylistrequest

import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestStatus

internal data class CreateStylistRequest(
    @field:Positive val stylistId: Long,
    @field:Valid val weather: OutfitWeatherDto,
    @field:Size(max = 2000) val wishes: String? = null,
)

internal data class CancelStylistRequest(@field:Positive val version: Long)

internal data class StylistRequestResponse(
    val id: Long,
    val ownerId: Long,
    val stylistId: Long,
    val wishes: String,
    val weather: OutfitWeatherDto,
    val status: StylistRequestStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
