package ru.itmo.clothesadvisor.dto.capsule

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.model.capsule.CapsuleSource

internal data class CreateCapsuleRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:ArraySchema(
        minItems = 1,
        maxItems = 20,
        uniqueItems = true,
        schema = Schema(type = "integer", format = "int64", minimum = "1", nullable = false),
        arraySchema = Schema(
            description = "Различные положительные ID своих вещей в порядке состава; null недопустим",
            requiredMode = Schema.RequiredMode.REQUIRED,
        ),
    )
    val itemIds: List<Long?>,
)

internal data class CapsuleResponse(
    val id: Long,
    val ownerId: Long,
    val name: String,
    val itemIds: List<Long>,
    val createdAt: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val source: CapsuleSource? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val weather: OutfitWeatherDto? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val occasion: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val preferredStyle: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val explanation: String? = null,
)
