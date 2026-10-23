package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.service.blockingAction
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveWardrobeService(
    private val items: WardrobeItemService,
    private val management: WardrobeManagementService,
    private val photos: WardrobeItemPhotoService,
) {
    fun create(ownerId: Long, request: CreateWardrobeItemRequest): Mono<WardrobeItemResponse> =
        blockingCall { items.create(ownerId, request) }

    fun createWithPhotos(
        ownerId: Long,
        request: CreateWardrobeItemRequest,
        files: List<ByteArray>,
    ): Mono<WardrobeItemResponse> =
        blockingCall { management.createWithPhotos(ownerId, request, files) }

    fun list(ownerId: Long, pageable: Pageable): Mono<List<WardrobeItemResponse>> =
        blockingCall { items.list(ownerId, pageable) }

    fun get(ownerId: Long, id: Long): Mono<WardrobeItemResponse> =
        blockingCall { items.get(ownerId, id) }

    fun update(
        ownerId: Long,
        id: Long,
        request: UpdateWardrobeItemRequest,
    ): Mono<WardrobeItemResponse> = blockingCall { items.update(ownerId, id, request) }

    fun delete(ownerId: Long, id: Long, expectedVersion: Long): Mono<Void> =
        blockingAction { items.delete(ownerId, id, expectedVersion) }

    fun addPhotos(
        ownerId: Long,
        itemId: Long,
        files: List<ByteArray>,
    ): Mono<List<WardrobeItemPhotoResponse>> = blockingCall { photos.add(ownerId, itemId, files) }

    fun listPhotos(ownerId: Long, itemId: Long): Mono<List<WardrobeItemPhotoResponse>> =
        blockingCall { photos.list(ownerId, itemId) }

    fun getPhotoContent(
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ): Mono<WardrobeItemPhotoContent> =
        blockingCall { photos.getOwnContent(ownerId, itemId, photoId) }

    fun deletePhoto(ownerId: Long, itemId: Long, photoId: Long): Mono<Void> =
        blockingAction { photos.delete(ownerId, itemId, photoId) }
}
