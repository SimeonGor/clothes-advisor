package ru.itmo.clothesadvisor.model.wardrobe

import java.time.Instant

internal data class WardrobeItemHistoryId(
    val itemId: Long,
    val version: Long,
)

internal class WardrobeItemHistory(item: WardrobeItem) {
    private val key = WardrobeItemHistoryId(requireNotNull(item.id), item.version)

    val name: String = item.name

    val categoryId: Long = requireNotNull(item.category.id)

    val color: String = item.color

    val material: String = item.material

    val modifiedAt: Instant = item.modifiedAt

    val id: WardrobeItemHistoryId get() = key

}
