package ru.itmo.clothesadvisor.dto.capsule

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

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
)
