package ru.itmo.clothesadvisor.model.wardrobe

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

@Table("wardrobe_item")
internal class WardrobeItem(
    val ownerId: Long,
    category: WardrobeCategory,
    name: String,
    color: String,
    material: String,
    val createdAt: Instant,
    modifiedAt: Instant = createdAt,
) {
    @Id
    var id: Long? = null
        internal set

    @Embedded.Empty(prefix = "category_")
    var category: WardrobeCategory = category
        internal set

    var name: String = name
        internal set

    var color: String = color
        internal set

    var material: String = material
        internal set

    var version: Long = 1
        internal set

    var modifiedAt: Instant = modifiedAt
        internal set

    fun replace(category: WardrobeCategory, name: String, color: String, material: String) {
        this.category = category
        this.name = name
        this.color = color
        this.material = material
    }
}
