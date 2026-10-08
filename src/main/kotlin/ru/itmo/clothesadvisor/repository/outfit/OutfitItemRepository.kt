package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.outfit.OutfitItem
import ru.itmo.clothesadvisor.model.outfit.OutfitItemId

@Repository
internal class OutfitItemRepository(private val jdbc: JdbcTemplate) {
    fun saveAll(items: Iterable<OutfitItem>) {
        val rows = items.map { arrayOf<Any>(it.id.outfitId, it.id.wardrobeItemId, it.position) }
        if (rows.isNotEmpty()) jdbc.batchUpdate("INSERT INTO outfit_item (outfit_id, wardrobe_item_id, position) VALUES (?, ?, ?)", rows)
    }

    fun findAllByKeyOutfitIdInOrderByPositionAsc(outfitIds: Collection<Long>): List<OutfitItem> {
        if (outfitIds.isEmpty()) return emptyList()
        return jdbc.query("SELECT * FROM outfit_item WHERE outfit_id IN (${outfitIds.joinToString { "?" }}) ORDER BY position",
            { row, _ -> OutfitItem(OutfitItemId(row.getLong("outfit_id"), row.getLong("wardrobe_item_id")), row.getInt("position")) },
            *outfitIds.toTypedArray())
    }
}
