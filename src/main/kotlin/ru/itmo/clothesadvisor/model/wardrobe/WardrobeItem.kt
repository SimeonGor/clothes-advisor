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
import jakarta.persistence.Version
import java.time.Instant
import ru.itmo.clothesadvisor.model.user.AppUser

@Entity
@Table(name = "wardrobe_item")
internal class WardrobeItem(
    owner: AppUser,
    category: WardrobeCategory,
    name: String,
    color: String,
    material: String,
    now: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "owner_id", nullable = false)
    val owner: AppUser = owner

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "category_id", nullable = false)
    var category: WardrobeCategory = category
        protected set

    @field:Column(nullable = false, columnDefinition = "text")
    var name: String = name
        protected set

    @field:Column(nullable = false, columnDefinition = "text")
    var color: String = color
        protected set

    @field:Column(nullable = false, columnDefinition = "text")
    var material: String = material
        protected set

    @field:Version
    @field:Column(nullable = false)
    var version: Long = 1
        protected set

    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant = now

    @field:Column(name = "modified_at", nullable = false)
    var modifiedAt: Instant = now
        protected set

    fun replace(category: WardrobeCategory, name: String, color: String, material: String, now: Instant) {
        this.category = category
        this.name = name
        this.color = color
        this.material = material
        modifiedAt = now
    }
}
