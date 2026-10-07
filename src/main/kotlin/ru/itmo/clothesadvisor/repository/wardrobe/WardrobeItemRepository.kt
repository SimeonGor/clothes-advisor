package ru.itmo.clothesadvisor.repository.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem

internal interface WardrobeItemRepository : Repository<WardrobeItem, Long> {
    fun findByIdAndOwnerId(id: Long, ownerId: Long): WardrobeItem?
    fun findAllByOwnerIdOrderByIdAsc(ownerId: Long, pageable: Pageable): List<WardrobeItem>
    fun save(item: WardrobeItem): WardrobeItem
    fun delete(item: WardrobeItem)
    fun flush()
}
