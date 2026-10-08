package ru.itmo.clothesadvisor.model.wardrobe

import java.time.Instant

internal class WardrobeItemPhoto(
    val itemId: Long,
    val s3Key: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
) {
    var id: Long? = null
        internal set
}
