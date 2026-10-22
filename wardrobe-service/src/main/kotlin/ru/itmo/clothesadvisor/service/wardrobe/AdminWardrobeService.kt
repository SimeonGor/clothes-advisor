package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.service.access.AdminContentAccessService

@Service
internal class AdminWardrobeService(
    private val access: AdminContentAccessService,
    private val items: WardrobeItemService,
    private val photos: WardrobeItemPhotoService,
) {
    fun listItems(adminId: Long, ownerId: Long, pageable: Pageable): List<WardrobeItemResponse> {
        access.requireAccess(adminId, ownerId)
        return items.list(ownerId, pageable)
    }

    fun getItem(adminId: Long, ownerId: Long, itemId: Long): WardrobeItemResponse {
        access.requireAccess(adminId, ownerId)
        return items.get(ownerId, itemId)
    }

    fun listPhotos(adminId: Long, ownerId: Long, itemId: Long): List<WardrobeItemPhotoResponse> {
        access.requireAccess(adminId, ownerId)
        return photos.list(ownerId, itemId)
    }

    fun getPhotoContent(
        adminId: Long,
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ): WardrobeItemPhotoContent {
        access.requireAccess(adminId, ownerId)
        val content = photos.getContent(ownerId, itemId, photoId)
        access.requireAccess(adminId, ownerId)
        return content
    }

    fun deletePhoto(adminId: Long, ownerId: Long, itemId: Long, photoId: Long) {
        access.requireAccess(adminId, ownerId)
        photos.delete(ownerId, itemId, photoId)
    }
}
