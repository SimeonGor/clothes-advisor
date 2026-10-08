package ru.itmo.clothesadvisor.repository.wardrobe

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem

internal interface WardrobeItemRepository : Repository<WardrobeItem, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WardrobeItem i where i.id = :id and i.owner.id = :ownerId")
    fun lockOwned(id: Long, ownerId: Long): WardrobeItem?
    fun findByIdAndOwnerId(id: Long, ownerId: Long): WardrobeItem?
    fun findAllByOwnerIdOrderByIdAsc(ownerId: Long, pageable: Pageable): List<WardrobeItem>
    fun save(item: WardrobeItem): WardrobeItem
    fun delete(item: WardrobeItem)
    fun flush()
}
