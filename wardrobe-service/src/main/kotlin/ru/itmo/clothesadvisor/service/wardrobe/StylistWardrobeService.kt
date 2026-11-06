package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse

@Service
internal class StylistWardrobeService(
    private val accounts: BlockingAccountClient,
    private val items: WardrobeItemService,
    private val photos: WardrobeItemPhotoService,
) {
    fun listItems(stylistId: Long, ownerId: Long, pageable: Pageable): List<WardrobeItemResponse> {
        accounts.requireStylistAccess(stylistId, ownerId)
        return items.list(ownerId, pageable)
    }

    fun getItem(stylistId: Long, ownerId: Long, itemId: Long): WardrobeItemResponse {
        accounts.requireStylistAccess(stylistId, ownerId)
        return items.get(ownerId, itemId)
    }

    fun listPhotos(stylistId: Long, ownerId: Long, itemId: Long): List<WardrobeItemPhotoResponse> {
        accounts.requireStylistAccess(stylistId, ownerId)
        return photos.list(ownerId, itemId)
    }

    fun getPhotoContent(
        stylistId: Long,
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ): WardrobeItemPhotoContent {
        accounts.requireStylistAccess(stylistId, ownerId)
        val content = photos.getContent(ownerId, itemId, photoId)
        accounts.requireStylistAccess(stylistId, ownerId)
        return content
    }
}
