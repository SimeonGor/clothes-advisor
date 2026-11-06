package ru.itmo.clothesadvisor.model.preferences

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("style_preferences")
internal data class StylePreferences(
    @Id val ownerId: Long,
    val preferredColorsAndMaterials: String,
    val avoidances: String,
    val additionalWishes: String,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
