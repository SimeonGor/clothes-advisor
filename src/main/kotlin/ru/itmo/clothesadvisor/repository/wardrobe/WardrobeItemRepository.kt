package ru.itmo.clothesadvisor.repository.wardrobe

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Pageable
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem

private const val SELECT_ITEM =
    """
    SELECT i.*, c.code AS category_code, c.name AS category_name
    FROM wardrobe_item i JOIN wardrobe_category c ON c.id = i.category_id
"""

internal interface WardrobeItemRepository : Repository<WardrobeItem, Long> {
    @Query(
        SELECT_ITEM +
            """
        WHERE i.owner_id = :ownerId
            AND EXISTS (SELECT 1 FROM wardrobe_item_photo p WHERE p.wardrobe_item_id = i.id)
        ORDER BY i.id LIMIT :limit
    """,
    )
    fun findOwnedItemsWithPhotos(ownerId: Long, limit: Int): List<WardrobeItem>

    fun findAllByOwnerIdAndIdInOrderByIdAsc(
        ownerId: Long,
        ids: Collection<Long>,
    ): List<WardrobeItem> = if (ids.isEmpty()) emptyList() else findOwnedRows(ownerId, ids)

    @Query(SELECT_ITEM + " WHERE i.owner_id = :ownerId AND i.id IN (:ids) ORDER BY i.id")
    fun findOwnedRows(ownerId: Long, ids: Collection<Long>): List<WardrobeItem>

    fun lockOwnedItemsForShare(ownerId: Long, ids: Collection<Long>): List<WardrobeItem> =
        if (ids.isEmpty()) emptyList() else findOwnedRowsForShare(ownerId, ids)

    @Query(
        SELECT_ITEM +
            " WHERE i.owner_id = :ownerId AND i.id IN (:ids) ORDER BY i.id FOR SHARE OF i",
    )
    fun findOwnedRowsForShare(ownerId: Long, ids: Collection<Long>): List<WardrobeItem>

    @Query(SELECT_ITEM + " WHERE i.id = :id AND i.owner_id = :ownerId FOR UPDATE OF i")
    fun lockOwned(id: Long, ownerId: Long): WardrobeItem?

    @Query(SELECT_ITEM + " WHERE i.id = :id AND i.owner_id = :ownerId")
    fun findByIdAndOwnerId(id: Long, ownerId: Long): WardrobeItem?

    fun countByOwnerIdAndIdIn(ownerId: Long, ids: Collection<Long>): Long =
        if (ids.isEmpty()) 0 else countOwnedRows(ownerId, ids)

    @Query("SELECT count(*) FROM wardrobe_item WHERE owner_id = :ownerId AND id IN (:ids)")
    fun countOwnedRows(ownerId: Long, ids: Collection<Long>): Long

    fun findAllByOwnerIdOrderByIdAsc(ownerId: Long, pageable: Pageable): List<WardrobeItem> =
        findPage(ownerId, pageable.pageSize, pageable.offset)

    @Query(SELECT_ITEM + " WHERE i.owner_id = :ownerId ORDER BY i.id LIMIT :limit OFFSET :offset")
    fun findPage(ownerId: Long, limit: Int, offset: Long): List<WardrobeItem>

    @Query(
        """
        WITH inserted AS (
            INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version)
            VALUES (:ownerId, :#{#category.id}, :name, :color, :material, 1) RETURNING *
        )
        SELECT i.*, c.code AS category_code, c.name AS category_name
        FROM inserted i JOIN wardrobe_category c ON c.id = i.category_id
    """,
    )
    fun create(
        ownerId: Long,
        category: WardrobeCategory,
        name: String,
        color: String,
        material: String,
    ): WardrobeItem

    fun update(item: WardrobeItem): WardrobeItem =
        updateRow(item)
            ?: throw OptimisticLockingFailureException("Wardrobe item ${item.id} version conflict")

    @Query(
        """
        WITH updated AS (
            UPDATE wardrobe_item SET category_id = :#{#item.category.id}, name = :#{#item.name},
                color = :#{#item.color}, material = :#{#item.material},
                modified_at = CURRENT_TIMESTAMP, version = version + 1
            WHERE id = :#{#item.id} AND version = :#{#item.version} RETURNING *
        )
        SELECT i.*, c.code AS category_code, c.name AS category_name
        FROM updated i JOIN wardrobe_category c ON c.id = i.category_id
    """,
    )
    fun updateRow(item: WardrobeItem): WardrobeItem?

    fun delete(item: WardrobeItem) {
        if (deleteRow(requireNotNull(item.id), item.version) != 1) {
            throw OptimisticLockingFailureException("Wardrobe item ${item.id} version conflict")
        }
    }

    @Modifying
    @Query("DELETE FROM wardrobe_item WHERE id = :id AND version = :version")
    fun deleteRow(id: Long, version: Long): Int
}
