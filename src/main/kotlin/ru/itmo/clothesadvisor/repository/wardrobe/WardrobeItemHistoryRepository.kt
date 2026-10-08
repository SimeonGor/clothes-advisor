package ru.itmo.clothesadvisor.repository.wardrobe

import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistory

@Repository
internal class WardrobeItemHistoryRepository(private val jdbc: JdbcTemplate) {
    fun save(history: WardrobeItemHistory) {
        jdbc.update("""
            INSERT INTO wardrobe_item_history (wardrobe_item_id, version, name, category_id, color, material, modified_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, history.id.itemId, history.id.version, history.name, history.categoryId, history.color, history.material,
            Timestamp.from(history.modifiedAt))
    }
}
