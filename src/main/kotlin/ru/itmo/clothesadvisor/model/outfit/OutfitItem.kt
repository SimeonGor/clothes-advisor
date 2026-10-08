package ru.itmo.clothesadvisor.model.outfit

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import org.springframework.data.domain.Persistable

@Embeddable
internal data class OutfitItemId(
    @field:Column(name = "outfit_id", nullable = false)
    val outfitId: Long,
    @field:Column(name = "wardrobe_item_id", nullable = false)
    val wardrobeItemId: Long,
) : Serializable

@Entity
@Table(name = "outfit_item")
internal class OutfitItem(
    @field:EmbeddedId
    private val key: OutfitItemId,
    @field:Column(nullable = false)
    val position: Int,
) : Persistable<OutfitItemId> {
    override fun getId(): OutfitItemId = key
    override fun isNew(): Boolean = true
}
