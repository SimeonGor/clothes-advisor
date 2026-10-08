package ru.itmo.clothesadvisor.repository.wardrobe

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem

internal interface WardrobeItemRepository : Repository<WardrobeItem, Long> {
    @Query(value = """
        SELECT i.* FROM wardrobe_item i WHERE i.owner_id = :ownerId
        AND EXISTS (SELECT 1 FROM wardrobe_item_photo p WHERE p.wardrobe_item_id = i.id)
        ORDER BY i.id LIMIT :limit
    """, nativeQuery = true)
    fun findOwnedItemsWithPhotos(ownerId: Long, limit: Int): List<WardrobeItem>

    fun findAllByOwnerIdAndIdInOrderByIdAsc(ownerId: Long, ids: Collection<Long>): List<WardrobeItem>

    @Query(value = "SELECT * FROM wardrobe_item WHERE owner_id = :ownerId AND id IN (:ids) ORDER BY id FOR SHARE", nativeQuery = true)
    fun lockOwnedItemsForShare(ownerId: Long, ids: Collection<Long>): List<WardrobeItem>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WardrobeItem i where i.id = :id and i.owner.id = :ownerId")
    fun lockOwned(id: Long, ownerId: Long): WardrobeItem?
    fun findByIdAndOwnerId(id: Long, ownerId: Long): WardrobeItem?
    fun countByOwnerIdAndIdIn(ownerId: Long, ids: Collection<Long>): Long
    fun findAllByOwnerIdOrderByIdAsc(ownerId: Long, pageable: Pageable): List<WardrobeItem>
    fun save(item: WardrobeItem): WardrobeItem
    fun delete(item: WardrobeItem)
    fun flush()
}
