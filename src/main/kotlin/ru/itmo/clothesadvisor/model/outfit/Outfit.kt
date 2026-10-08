package ru.itmo.clothesadvisor.model.outfit

import java.time.Instant

internal enum class OutfitSource { USER, STYLIST, AI }

internal class Outfit(
    val ownerId: Long,
    val authorId: Long,
    val source: OutfitSource,
    val name: String,
    val createdAt: Instant,
) {
    var id: Long? = null
        internal set
}
