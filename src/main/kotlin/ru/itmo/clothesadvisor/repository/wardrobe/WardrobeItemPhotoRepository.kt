package ru.itmo.clothesadvisor.repository.wardrobe

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto

internal interface WardrobeItemPhotoRepository : Repository<WardrobeItemPhoto, Long> {
    fun findFirstByItemIdOrderByIdAsc(itemId: Long): WardrobeItemPhoto?

    fun findAllByItemIdOrderByIdAsc(itemId: Long): List<WardrobeItemPhoto>

    @Query("""
        SELECT p.* FROM wardrobe_item_photo p JOIN wardrobe_item i ON i.id = p.wardrobe_item_id
        WHERE p.id = :id AND p.wardrobe_item_id = :itemId AND i.owner_id = :ownerId
    """)
    fun findByIdAndItemIdAndItemOwnerId(id: Long, itemId: Long, ownerId: Long): WardrobeItemPhoto?

    fun countByItemId(itemId: Long): Long

    @Query("""
        INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes)
        VALUES (:itemId, :s3Key, :contentType, :sizeBytes) RETURNING *
    """)
    fun create(itemId: Long, s3Key: String, contentType: String, sizeBytes: Long): WardrobeItemPhoto

    @Modifying
    @Query("DELETE FROM wardrobe_item_photo WHERE id = :#{#photo.id}")
    fun delete(photo: WardrobeItemPhoto)
}
