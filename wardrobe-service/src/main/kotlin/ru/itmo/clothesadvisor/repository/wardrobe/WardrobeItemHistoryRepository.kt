package ru.itmo.clothesadvisor.repository.wardrobe

import java.time.Instant
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistoryId

internal interface WardrobeItemHistoryRepository :
    Repository<WardrobeItemHistory, WardrobeItemHistoryId> {
    fun insert(history: WardrobeItemHistory) =
        insert(
            history.id.itemId,
            history.id.version,
            history.name,
            history.categoryId,
            history.color,
            history.material,
            history.modifiedAt,
        )

    @Modifying
    @Query(
        """
        INSERT INTO wardrobe_item_history (wardrobe_item_id, version, name, category_id, color, material, modified_at)
        VALUES (:itemId, :version, :name, :categoryId, :color, :material, :modifiedAt)
    """,
    )
    fun insert(
        itemId: Long,
        version: Long,
        name: String,
        categoryId: Long,
        color: String,
        material: String,
        modifiedAt: Instant,
    )
}
