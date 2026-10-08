package ru.itmo.clothesadvisor.model.outfit

internal data class OutfitItemId(
    val outfitId: Long,
    val wardrobeItemId: Long,
)

internal class OutfitItem(
    private val key: OutfitItemId,
    val position: Int,
) {
    val id: OutfitItemId get() = key
}
