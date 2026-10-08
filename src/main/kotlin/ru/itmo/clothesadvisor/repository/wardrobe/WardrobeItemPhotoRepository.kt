package ru.itmo.clothesadvisor.repository.wardrobe

import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto

internal interface WardrobeItemPhotoRepository : Repository<WardrobeItemPhoto, Long> {
    fun findAllByItemIdOrderByIdAsc(itemId: Long): List<WardrobeItemPhoto>
    fun findByIdAndItemIdAndItemOwnerId(id: Long, itemId: Long, ownerId: Long): WardrobeItemPhoto?
    fun countByItemId(itemId: Long): Long
    fun save(photo: WardrobeItemPhoto): WardrobeItemPhoto
    fun delete(photo: WardrobeItemPhoto)
}
