package ru.itmo.clothesadvisor.dto.ai

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto

internal class CreateAiCapsuleRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:Valid val weather: OutfitWeatherDto,
    @field:ArraySchema(
        minItems = 1,
        uniqueItems = true,
        schema = Schema(type = "integer", format = "int64", minimum = "1", nullable = false),
        arraySchema = Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "Без поля или с null — весь доступный гардероб с фото. Иначе различные положительные ID без null. Требуется 1..20 кандидатов после фильтрации."),
    )
    val candidateItemIds: List<Long?>? = null,
    @field:Size(max = 300) val occasion: String? = null,
    @field:Size(max = 300) val preferredStyle: String? = null,
)
