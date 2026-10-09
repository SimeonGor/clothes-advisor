package ru.itmo.clothesadvisor.model.wardrobe

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal data class WardrobeItemHistoryId(
    @Column("wardrobe_item_id") val itemId: Long,
    val version: Long,
)

@Table("wardrobe_item_history")
internal class WardrobeItemHistory @PersistenceCreator constructor(
    @Id @Embedded.Empty val id: WardrobeItemHistoryId,
    val name: String,
    val categoryId: Long,
    val color: String,
    val material: String,
    val modifiedAt: Instant,
) {
    constructor(item: WardrobeItem) : this(
        WardrobeItemHistoryId(requireNotNull(item.id), item.version),
        item.name, requireNotNull(item.category.id), item.color, item.material, item.modifiedAt,
    )
}
