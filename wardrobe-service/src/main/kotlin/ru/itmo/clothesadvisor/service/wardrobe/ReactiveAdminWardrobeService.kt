package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.service.blockingAction
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveAdminWardrobeService(private val wardrobe: AdminWardrobeService) {
    fun listItems(
        adminId: Long,
        ownerId: Long,
        pageable: Pageable,
    ): Mono<List<WardrobeItemResponse>> = blockingCall { wardrobe.listItems(adminId, ownerId, pageable) }

    fun getItem(adminId: Long, ownerId: Long, itemId: Long): Mono<WardrobeItemResponse> =
        blockingCall { wardrobe.getItem(adminId, ownerId, itemId) }

    fun listPhotos(
        adminId: Long,
        ownerId: Long,
        itemId: Long,
    ): Mono<List<WardrobeItemPhotoResponse>> =
        blockingCall { wardrobe.listPhotos(adminId, ownerId, itemId) }

    fun getPhotoContent(
        adminId: Long,
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ): Mono<WardrobeItemPhotoContent> =
        blockingCall { wardrobe.getPhotoContent(adminId, ownerId, itemId, photoId) }

    fun deletePhoto(adminId: Long, ownerId: Long, itemId: Long, photoId: Long): Mono<Void> =
        blockingAction { wardrobe.deletePhoto(adminId, ownerId, itemId, photoId) }
}
