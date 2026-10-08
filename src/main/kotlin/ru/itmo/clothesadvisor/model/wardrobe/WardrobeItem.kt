package ru.itmo.clothesadvisor.model.wardrobe

import java.time.Instant

internal class WardrobeItem(
    val ownerId: Long,
    category: WardrobeCategory,
    name: String,
    color: String,
    material: String,
    now: Instant,
) {
    var id: Long? = null
        internal set

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

    val createdAt: Instant = now

    var modifiedAt: Instant = now
        internal set

    fun replace(category: WardrobeCategory, name: String, color: String, material: String) {
        this.category = category
        this.name = name
        this.color = color
        this.material = material
    }
}
