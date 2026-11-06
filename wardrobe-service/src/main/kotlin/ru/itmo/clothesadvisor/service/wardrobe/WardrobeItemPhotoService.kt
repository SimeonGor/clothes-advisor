package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto
import ru.itmo.clothesadvisor.model.wardrobe.PhotoRequestException
import ru.itmo.clothesadvisor.model.wardrobe.PhotoRequestFailure
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.ADD_WARDROBE_PHOTOS
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.core.type.TypeReference

@Service
internal class WardrobeItemPhotoService(
    private val items: WardrobeItemRepository,
    private val photos: WardrobeItemPhotoRepository,
    private val storage: S3PhotoStorage,
    private val users: CurrentUserService,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun add(
        ownerId: Long,
        itemId: Long,
        files: List<ByteArray>,
        key: String? = null,
    ): List<WardrobeItemPhotoResponse> {
        users.requireActive(ownerId)
        if (key != null) findOwnedItem(ownerId, itemId)
        return idempotency.execute(
            ownerId, ADD_WARDROBE_PHOTOS, key, listOf(ownerId, itemId),
            object : TypeReference<List<WardrobeItemPhotoResponse>>() {}, files,
        ) { completion ->
            val existingCount = transaction.execute {
                findOwnedItem(ownerId, itemId)
                photos.countByItemId(itemId)
            }

            val validatedPhotos = validatePhotos(files, allowEmpty = false)
            checkPhotoCapacity(existingCount, validatedPhotos.size)

            upload(validatedPhotos)

            users.requireActive(ownerId)
            completion.commit { saveMetadata(ownerId, itemId, validatedPhotos) }
        }
    }

    fun list(ownerId: Long, itemId: Long): List<WardrobeItemPhotoResponse> = transaction.execute {
        findOwnedItem(ownerId, itemId)
        photos.findAllByItemIdOrderByIdAsc(itemId).map(::toResponse)
    }

    fun getOwnContent(ownerId: Long, itemId: Long, photoId: Long): WardrobeItemPhotoContent {
        users.requireActive(ownerId)
        val content = getContent(ownerId, itemId, photoId)
        users.requireActive(ownerId)
        return content
    }

    fun getContent(ownerId: Long, itemId: Long, photoId: Long): WardrobeItemPhotoContent {
        val snapshot = transaction.execute { findOwnedPhoto(ownerId, itemId, photoId) }
        val bytes = storage.get(snapshot.s3Key, snapshot.sizeBytes)
        transaction.executeWithoutResult { findOwnedPhoto(ownerId, itemId, photoId) }

        return WardrobeItemPhotoContent(snapshot.contentType, bytes)
    }

    fun delete(ownerId: Long, itemId: Long, photoId: Long) {
        transaction.executeWithoutResult {
            items.lockOwned(itemId, ownerId) ?: throw EntityNotFoundException()
            photos.delete(findOwnedPhoto(ownerId, itemId, photoId))
        }
    }

    private fun findOwnedItem(ownerId: Long, itemId: Long): WardrobeItem =
        items.findByIdAndOwnerId(itemId, ownerId) ?: throw EntityNotFoundException()

    private fun findOwnedPhoto(ownerId: Long, itemId: Long, photoId: Long): WardrobeItemPhoto =
        photos.findByIdAndItemIdAndItemOwnerId(photoId, itemId, ownerId)
            ?: throw EntityNotFoundException()

    private fun checkPhotoCapacity(existingCount: Long, additionalCount: Int) {
        if (existingCount + additionalCount > MAX_ITEM_PHOTOS) {
            throw PhotoRequestException(PhotoRequestFailure.CAPACITY_EXCEEDED)
        }
    }

    fun upload(validatedPhotos: List<ValidatedPhoto>) = validatedPhotos.forEach {
        storage.put(it.s3Key, it.contentType, it.bytes)
    }

    fun saveMetadata(
        ownerId: Long,
        itemId: Long,
        validatedPhotos: List<ValidatedPhoto>,
    ): List<WardrobeItemPhotoResponse> = transaction.execute {
        val item = items.lockOwned(itemId, ownerId) ?: throw EntityNotFoundException()
        checkPhotoCapacity(photos.countByItemId(itemId), validatedPhotos.size)

        validatedPhotos.map {
            toResponse(
                photos.create(
                    requireNotNull(item.id),
                    it.s3Key,
                    it.contentType,
                    it.bytes.size.toLong(),
                ),
            )
        }
    }

    private fun toResponse(photo: WardrobeItemPhoto) =
        WardrobeItemPhotoResponse(
            requireNotNull(photo.id),
            photo.itemId,
            photo.contentType,
            photo.sizeBytes,
            photo.createdAt,
        )
}
