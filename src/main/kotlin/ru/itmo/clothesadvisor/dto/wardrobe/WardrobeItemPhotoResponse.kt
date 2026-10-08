package ru.itmo.clothesadvisor.dto.wardrobe

import java.time.Instant

internal data class WardrobeItemPhotoResponse(
    val id: Long,
    val itemId: Long,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)
