package ru.itmo.clothesadvisor.model.wardrobe

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import org.springframework.data.domain.Persistable

@Embeddable
internal data class WardrobeItemHistoryId(
    @field:Column(name = "garment_id", nullable = false)
    val itemId: Long,
    @field:Column(nullable = false)
    val version: Long,
) : Serializable

@Entity
@Table(name = "garment_history")
internal class WardrobeItemHistory(item: WardrobeItem, archivedAt: Instant) : Persistable<WardrobeItemHistoryId> {
    @field:EmbeddedId
    private val key = WardrobeItemHistoryId(requireNotNull(item.id), item.version)

    @field:Column(nullable = false, columnDefinition = "text")
    val name: String = item.name

    @field:Column(name = "category_id", nullable = false)
    val categoryId: Long = requireNotNull(item.category.id)

    @field:Column(nullable = false, columnDefinition = "text")
    val color: String = item.color

    @field:Column(nullable = false, columnDefinition = "text")
    val material: String = item.material

    @field:Column(name = "modified_at", nullable = false)
    val modifiedAt: Instant = item.modifiedAt

    @field:Column(name = "archived_at", nullable = false)
    val archivedAt: Instant = archivedAt

    override fun getId(): WardrobeItemHistoryId = key

    override fun isNew(): Boolean = true
}
