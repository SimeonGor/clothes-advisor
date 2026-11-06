package ru.itmo.clothesadvisor.repository.preferences

import java.time.Instant
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.preferences.StylePreferencesHistory
import ru.itmo.clothesadvisor.model.preferences.StylePreferencesHistoryId

internal interface StylePreferencesHistoryRepository :
    Repository<StylePreferencesHistory, StylePreferencesHistoryId> {
    fun insert(history: StylePreferencesHistory) =
        insert(
            history.id.ownerId,
            history.id.version,
            history.preferredColorsAndMaterials,
            history.avoidances,
            history.additionalWishes,
            history.createdAt,
            history.modifiedAt,
        )

    @Modifying
    @Query(
        """
        INSERT INTO style_preferences_history
            (owner_id, version, preferred_colors_and_materials, avoidances, additional_wishes,
             created_at, modified_at)
        VALUES (:ownerId, :version, :preferredColorsAndMaterials, :avoidances,
            :additionalWishes, :createdAt, :modifiedAt)
        """,
    )
    fun insert(
        ownerId: Long,
        version: Long,
        preferredColorsAndMaterials: String,
        avoidances: String,
        additionalWishes: String,
        createdAt: Instant,
        modifiedAt: Instant,
    )
}
