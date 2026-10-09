package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.service.access.AccessGrantService

@Service
internal class StylistWardrobeService(
    private val access: AccessGrantService,
    private val items: WardrobeItemService,
    private val photos: WardrobeItemPhotoService,
) {
    fun listItems(stylistId: Long, ownerId: Long, pageable: Pageable): List<WardrobeItemResponse> {
        access.requireAccess(stylistId, ownerId)
        return items.list(ownerId, pageable)
    }

    fun getItem(stylistId: Long, ownerId: Long, itemId: Long): WardrobeItemResponse {
        access.requireAccess(stylistId, ownerId)
        return items.get(ownerId, itemId)
    }

    fun listPhotos(stylistId: Long, ownerId: Long, itemId: Long): List<WardrobeItemPhotoResponse> {
        access.requireAccess(stylistId, ownerId)
        return photos.list(ownerId, itemId)
    }

    fun getPhotoContent(
        stylistId: Long,
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ): WardrobeItemPhotoContent {
        access.requireAccess(stylistId, ownerId)
        val content = photos.getContent(ownerId, itemId, photoId)
        access.requireAccess(stylistId, ownerId)
        return content
    }
}
