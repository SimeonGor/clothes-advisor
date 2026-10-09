package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.model.outfit.OutfitItem

internal class OutfitItemBatchInsertImpl(private val jdbc: JdbcTemplate) : OutfitItemBatchInsert {
    override fun insertAll(items: Iterable<OutfitItem>) {
        val rows = items.map { arrayOf<Any>(it.id.outfitId, it.id.wardrobeItemId, it.position) }
        if (rows.isNotEmpty()) {
            jdbc.batchUpdate("INSERT INTO outfit_item (outfit_id, wardrobe_item_id, position) VALUES (?, ?, ?)", rows)
        }
    }
}
