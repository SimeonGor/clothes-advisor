package ru.itmo.clothesadvisor.model.outfit

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

internal enum class OutfitSource {
    USER,
    STYLIST,
    AI,
}

@Table("outfit")
internal class Outfit(
    val ownerId: Long,
    val authorId: Long,
    val source: OutfitSource,
    val name: String,
    val createdAt: Instant,
    val occasion: String? = null,
    val preferredStyle: String? = null,
    val explanation: String? = null,
) {
    @Id
    var id: Long? = null
        internal set
}
