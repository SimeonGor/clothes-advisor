package ru.itmo.clothesadvisor.model.outfit

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal data class OutfitItemId(
    val outfitId: Long,
    val wardrobeItemId: Long,
)

@Table("outfit_item")
internal class OutfitItem(
    @Id @Embedded.Empty val id: OutfitItemId,
    val position: Int,
)
