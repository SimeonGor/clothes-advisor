package ru.itmo.clothesadvisor.model.wardrobe

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("wardrobe_item_photo")
internal class WardrobeItemPhoto(
    @Column("wardrobe_item_id") val itemId: Long,
    val s3Key: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
) {
    @Id
    var id: Long? = null
        internal set
}
