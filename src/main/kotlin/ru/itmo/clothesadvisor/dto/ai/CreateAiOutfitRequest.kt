package ru.itmo.clothesadvisor.dto.ai

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto

internal class CreateAiOutfitRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:Valid val weather: OutfitWeatherDto,
    @field:ArraySchema(
        minItems = 1,
        uniqueItems = true,
        schema = Schema(type = "integer", format = "int64", minimum = "1", nullable = false),
        arraySchema =
            Schema(
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                description =
                    "Без поля или с null — весь гардероб. Иначе различные положительные ID без null. После исключения вещей без фото должно остаться 1..20 кандидатов.",
            ),
    )
    val candidateItemIds: List<Long?>? = null,
)
