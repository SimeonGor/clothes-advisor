package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveStylistWardrobeService(private val wardrobe: StylistWardrobeService) {
    fun listItems(
        stylistId: Long,
        ownerId: Long,
        pageable: Pageable,
    ): Mono<List<WardrobeItemResponse>> =
        blockingCall { wardrobe.listItems(stylistId, ownerId, pageable) }

    fun getItem(stylistId: Long, ownerId: Long, itemId: Long): Mono<WardrobeItemResponse> =
        blockingCall { wardrobe.getItem(stylistId, ownerId, itemId) }

    fun listPhotos(
        stylistId: Long,
        ownerId: Long,
        itemId: Long,
    ): Mono<List<WardrobeItemPhotoResponse>> =
        blockingCall { wardrobe.listPhotos(stylistId, ownerId, itemId) }

    fun getPhotoContent(
        stylistId: Long,
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ): Mono<WardrobeItemPhotoContent> =
        blockingCall { wardrobe.getPhotoContent(stylistId, ownerId, itemId, photoId) }
}
