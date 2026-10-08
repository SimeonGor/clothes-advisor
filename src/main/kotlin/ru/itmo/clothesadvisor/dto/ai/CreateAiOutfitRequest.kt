package ru.itmo.clothesadvisor.dto.ai

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto

internal class CreateAiOutfitRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:Valid val weather: OutfitWeatherDto,
    val candidateItemIds: List<Long?>? = null,
)
