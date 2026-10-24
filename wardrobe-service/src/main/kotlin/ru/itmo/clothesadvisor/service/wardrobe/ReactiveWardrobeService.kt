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
    fun create(ownerId: Long, request: CreateWardrobeItemRequest, key: String? = null): Mono<WardrobeItemResponse> =
        blockingCall { management.create(ownerId, request, key) }

    fun createWithPhotos(
        ownerId: Long,
        request: CreateWardrobeItemRequest,
        files: List<ByteArray>,
        key: String? = null,
    ): Mono<WardrobeItemResponse> =
        blockingCall { management.createWithPhotos(ownerId, request, files, key) }

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
        key: String? = null,
    ): Mono<List<WardrobeItemPhotoResponse>> = blockingCall { photos.add(ownerId, itemId, files, key) }

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
