package ru.itmo.clothesadvisor.dto.preferences

import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

internal data class PutStylePreferencesRequest(
    @field:Size(max = 2000) val preferredColorsAndMaterials: String? = null,
    @field:Size(max = 2000) val avoidances: String? = null,
    @field:Size(max = 2000) val additionalWishes: String? = null,
    @field:Positive val version: Long? = null,
)

internal data class StylePreferencesResponse(
    val preferredColorsAndMaterials: String,
    val avoidances: String,
    val additionalWishes: String,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
