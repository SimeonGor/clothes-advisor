package ru.itmo.clothesadvisor.dto.ai

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

internal class CreateAiOutfitReplacementRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:ArraySchema(minItems = 1, uniqueItems = true,
        schema = Schema(type = "integer", format = "int64", minimum = "1", nullable = false))
    val replaceItemIds: List<Long?>,
    @field:ArraySchema(minItems = 1, uniqueItems = true,
        schema = Schema(type = "integer", format = "int64", minimum = "1", nullable = false),
        arraySchema = Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "Без поля или с null — гардероб. Сохраняемые вещи добавляются автоматически, заменяемые исключаются. Итог: до 20 вещей с фото и хотя бы одна новая вещь."))
    val candidateItemIds: List<Long?>? = null,
)
