package ru.itmo.clothesadvisor.repository.wardrobe

import java.sql.ResultSet
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto

@Repository
internal class WardrobeItemPhotoRepository(private val jdbc: JdbcTemplate) {
    private fun map(row: ResultSet) = WardrobeItemPhoto(row.getLong("wardrobe_item_id"), row.getString("s3_key"),
        row.getString("content_type"), row.getLong("size_bytes"), row.getTimestamp("created_at").toInstant()).apply {
        id = row.getLong("id")
    }

    fun findFirstByItemIdOrderByIdAsc(itemId: Long): WardrobeItemPhoto? =
        jdbc.query("SELECT * FROM wardrobe_item_photo WHERE wardrobe_item_id = ? ORDER BY id LIMIT 1", { row, _ -> map(row) }, itemId).singleOrNull()

    fun findAllByItemIdOrderByIdAsc(itemId: Long): List<WardrobeItemPhoto> =
        jdbc.query("SELECT * FROM wardrobe_item_photo WHERE wardrobe_item_id = ? ORDER BY id", { row, _ -> map(row) }, itemId)

    fun findByIdAndItemIdAndItemOwnerId(id: Long, itemId: Long, ownerId: Long): WardrobeItemPhoto? =
        jdbc.query("""
            SELECT p.* FROM wardrobe_item_photo p JOIN wardrobe_item i ON i.id = p.wardrobe_item_id
            WHERE p.id = ? AND p.wardrobe_item_id = ? AND i.owner_id = ?
        """, { row, _ -> map(row) }, id, itemId, ownerId).singleOrNull()

    fun countByItemId(itemId: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM wardrobe_item_photo WHERE wardrobe_item_id = ?", Long::class.java, itemId)!!

    fun create(itemId: Long, s3Key: String, contentType: String, sizeBytes: Long): WardrobeItemPhoto = jdbc.queryForObject("""
        INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes)
        VALUES (?, ?, ?, ?) RETURNING *
    """, { row, _ -> map(row) }, itemId, s3Key, contentType, sizeBytes)

    fun delete(photo: WardrobeItemPhoto) {
        jdbc.update("DELETE FROM wardrobe_item_photo WHERE id = ?", photo.id)
    }
}
