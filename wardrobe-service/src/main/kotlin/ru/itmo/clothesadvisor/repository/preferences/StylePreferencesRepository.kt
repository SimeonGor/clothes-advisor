package ru.itmo.clothesadvisor.repository.preferences

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.preferences.StylePreferences

internal interface StylePreferencesRepository : Repository<StylePreferences, Long> {
    @Query("SELECT * FROM style_preferences WHERE owner_id = :ownerId")
    fun findByOwnerId(ownerId: Long): StylePreferences?

    @Query(
        """
        INSERT INTO style_preferences
            (owner_id, preferred_colors_and_materials, avoidances, additional_wishes, version)
        VALUES (:ownerId, :preferredColorsAndMaterials, :avoidances, :additionalWishes, 1)
        ON CONFLICT (owner_id) DO NOTHING RETURNING *
        """,
    )
    fun create(
        ownerId: Long,
        preferredColorsAndMaterials: String,
        avoidances: String,
        additionalWishes: String,
    ): StylePreferences?

    @Query(
        """
        UPDATE style_preferences
        SET preferred_colors_and_materials = :#{#preferences.preferredColorsAndMaterials},
            avoidances = :#{#preferences.avoidances},
            additional_wishes = :#{#preferences.additionalWishes},
            version = version + 1, modified_at = CURRENT_TIMESTAMP
        WHERE owner_id = :#{#preferences.ownerId} AND version = :#{#preferences.version}
        RETURNING *
        """,
    )
    fun update(preferences: StylePreferences): StylePreferences?
}
