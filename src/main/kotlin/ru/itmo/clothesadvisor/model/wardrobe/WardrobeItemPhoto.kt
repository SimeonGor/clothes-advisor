package ru.itmo.clothesadvisor.model.wardrobe

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "wardrobe_item_photo")
internal class WardrobeItemPhoto(
    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "wardrobe_item_id", nullable = false)
    val item: WardrobeItem,
    @field:Column(name = "s3_key", nullable = false, unique = true, columnDefinition = "text")
    val s3Key: String,
    @field:Column(name = "content_type", nullable = false, columnDefinition = "text")
    val contentType: String,
    @field:Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,
    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
