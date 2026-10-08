package ru.itmo.clothesadvisor.repository.wardrobe

import java.sql.ResultSet
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem

@Repository
internal class WardrobeItemRepository(private val jdbc: JdbcTemplate) {
    private val select = """
        SELECT i.*, c.code AS category_code, c.name AS category_name
        FROM wardrobe_item i JOIN wardrobe_category c ON c.id = i.category_id
    """

    private fun map(row: ResultSet, knownCategory: WardrobeCategory? = null): WardrobeItem {
        val category = knownCategory ?: WardrobeCategory(row.getString("category_code"), row.getString("category_name")).apply {
            id = row.getLong("category_id")
        }
        return WardrobeItem(row.getLong("owner_id"), category, row.getString("name"), row.getString("color"),
            row.getString("material"), row.getTimestamp("created_at").toInstant()).apply {
            id = row.getLong("id")
            version = row.getLong("version")
            modifiedAt = row.getTimestamp("modified_at").toInstant()
        }
    }

    fun findOwnedItemsWithPhotos(ownerId: Long, limit: Int): List<WardrobeItem> =
        jdbc.query("$select WHERE i.owner_id = ? AND EXISTS (SELECT 1 FROM wardrobe_item_photo p WHERE p.wardrobe_item_id = i.id) ORDER BY i.id LIMIT ?",
            { row, _ -> map(row) }, ownerId, limit)

    fun findAllByOwnerIdAndIdInOrderByIdAsc(ownerId: Long, ids: Collection<Long>): List<WardrobeItem> =
        ownedItems(ownerId, ids, "")

    fun lockOwnedItemsForShare(ownerId: Long, ids: Collection<Long>): List<WardrobeItem> =
        ownedItems(ownerId, ids, " FOR SHARE OF i")

    private fun ownedItems(ownerId: Long, ids: Collection<Long>, lock: String): List<WardrobeItem> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query("$select WHERE i.owner_id = ? AND i.id IN (${ids.joinToString { "?" }}) ORDER BY i.id$lock",
            { row, _ -> map(row) }, ownerId, *ids.toTypedArray())
    }

    fun lockOwned(id: Long, ownerId: Long): WardrobeItem? =
        jdbc.query("$select WHERE i.id = ? AND i.owner_id = ? FOR UPDATE OF i", { row, _ -> map(row) }, id, ownerId).singleOrNull()

    fun findByIdAndOwnerId(id: Long, ownerId: Long): WardrobeItem? =
        jdbc.query("$select WHERE i.id = ? AND i.owner_id = ?", { row, _ -> map(row) }, id, ownerId).singleOrNull()

    fun countByOwnerIdAndIdIn(ownerId: Long, ids: Collection<Long>): Long {
        if (ids.isEmpty()) return 0
        return jdbc.queryForObject("SELECT count(*) FROM wardrobe_item WHERE owner_id = ? AND id IN (${ids.joinToString { "?" }})",
            Long::class.java, ownerId, *ids.toTypedArray())!!
    }

    fun findAllByOwnerIdOrderByIdAsc(ownerId: Long, pageable: Pageable): List<WardrobeItem> =
        jdbc.query("$select WHERE i.owner_id = ? ORDER BY i.id LIMIT ? OFFSET ?", { row, _ -> map(row) },
            ownerId, pageable.pageSize, pageable.offset)

    fun create(ownerId: Long, category: WardrobeCategory, name: String, color: String, material: String): WardrobeItem =
        jdbc.queryForObject("""
            INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version)
            VALUES (?, ?, ?, ?, ?, 1) RETURNING *
        """, { row, _ -> map(row, category) }, ownerId, category.id, name, color, material)

    fun update(item: WardrobeItem): WardrobeItem = jdbc.query("""
        UPDATE wardrobe_item SET category_id = ?, name = ?, color = ?, material = ?, modified_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = ? AND version = ? RETURNING *
    """, { row, _ -> map(row, item.category) }, item.category.id, item.name, item.color, item.material, item.id, item.version).singleOrNull()
        ?: throw OptimisticLockingFailureException("Wardrobe item ${item.id} version conflict")

    fun delete(item: WardrobeItem) {
        if (jdbc.update("DELETE FROM wardrobe_item WHERE id = ? AND version = ?", item.id, item.version) != 1)
            throw OptimisticLockingFailureException("Wardrobe item ${item.id} version conflict")
    }
}
