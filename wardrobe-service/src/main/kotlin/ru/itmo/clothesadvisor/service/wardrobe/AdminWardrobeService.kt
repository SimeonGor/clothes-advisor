package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse

@Service
internal class AdminWardrobeService(
    private val accounts: AccountClient,
    private val items: WardrobeItemService,
    private val photos: WardrobeItemPhotoService,
) {
    fun listItems(adminId: Long, ownerId: Long, pageable: Pageable): List<WardrobeItemResponse> {
        accounts.requireAdminAccess(adminId, ownerId)
        return items.list(ownerId, pageable)
    }

    fun getItem(adminId: Long, ownerId: Long, itemId: Long): WardrobeItemResponse {
        accounts.requireAdminAccess(adminId, ownerId)
        return items.get(ownerId, itemId)
    }

    fun listPhotos(adminId: Long, ownerId: Long, itemId: Long): List<WardrobeItemPhotoResponse> {
        accounts.requireAdminAccess(adminId, ownerId)
        return photos.list(ownerId, itemId)
    }

    fun getPhotoContent(
        adminId: Long,
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ): WardrobeItemPhotoContent {
        accounts.requireAdminAccess(adminId, ownerId)
        val content = photos.getContent(ownerId, itemId, photoId)
        accounts.requireAdminAccess(adminId, ownerId)
        return content
    }

    fun deletePhoto(adminId: Long, ownerId: Long, itemId: Long, photoId: Long) {
        accounts.requireAdminAccess(adminId, ownerId)
        photos.delete(ownerId, itemId, photoId)
    }
}
