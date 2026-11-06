package ru.itmo.clothesadvisor.repository.capsule

import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.model.capsule.CapsuleItem

internal class CapsuleItemBatchInsertImpl(private val jdbc: JdbcTemplate) : CapsuleItemBatchInsert {
    override fun insertAll(items: Iterable<CapsuleItem>) {
        val rows = items.map { arrayOf<Any>(it.id.capsuleId, it.id.wardrobeItemId, it.position) }
        if (rows.isNotEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO capsule_item (capsule_id, wardrobe_item_id, position) VALUES (?, ?, ?)",
                rows,
            )
        }
    }
}
