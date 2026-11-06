package ru.itmo.clothesadvisor.model.preferences

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal data class StylePreferencesHistoryId(val ownerId: Long, val version: Long)

@Table("style_preferences_history")
internal data class StylePreferencesHistory(
    @Id @Embedded.Empty val id: StylePreferencesHistoryId,
    val preferredColorsAndMaterials: String,
    val avoidances: String,
    val additionalWishes: String,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
