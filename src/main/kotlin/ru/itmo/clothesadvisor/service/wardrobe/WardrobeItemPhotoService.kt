package ru.itmo.clothesadvisor.service.wardrobe

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@Service
internal class WardrobeItemPhotoService(
    private val items: WardrobeItemRepository,
    private val photos: WardrobeItemPhotoRepository,
    private val storage: S3PhotoStorage,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun add(ownerId: Long, itemId: Long, files: List<MultipartFile>): List<WardrobeItemPhotoResponse> {
        val existingCount = transaction.execute {
            owned(ownerId, itemId)
            photos.countByItemId(itemId)
        }
        val validated = validatePhotos(files, allowEmpty = false)
        checkPhotoCapacity(existingCount, validated.size)
        upload(validated)
        return saveMetadata(ownerId, itemId, validated)
    }

    fun list(ownerId: Long, itemId: Long): List<WardrobeItemPhotoResponse> = transaction.execute {
        owned(ownerId, itemId)
        photos.findAllByItemIdOrderByIdAsc(itemId).map(::response)
    }

    fun getContent(ownerId: Long, itemId: Long, photoId: Long): WardrobeItemPhotoContent {
        val snapshot = transaction.execute { photo(ownerId, itemId, photoId) }
        val bytes = storage.get(snapshot.s3Key, snapshot.sizeBytes)
        transaction.executeWithoutResult { photo(ownerId, itemId, photoId) }
        return WardrobeItemPhotoContent(snapshot.contentType, bytes)
    }

    fun delete(ownerId: Long, itemId: Long, photoId: Long) {
        transaction.executeWithoutResult {
            items.lockOwned(itemId, ownerId) ?: throw EntityNotFoundException()
            photos.delete(photo(ownerId, itemId, photoId))
        }
    }

    private fun owned(ownerId: Long, itemId: Long): WardrobeItem =
        items.findByIdAndOwnerId(itemId, ownerId) ?: throw EntityNotFoundException()

    private fun photo(ownerId: Long, itemId: Long, photoId: Long): WardrobeItemPhoto =
        photos.findByIdAndItemIdAndItemOwnerId(photoId, itemId, ownerId) ?: throw EntityNotFoundException()

    private fun checkPhotoCapacity(existingCount: Long, additionalCount: Int) {
        if (existingCount + additionalCount > MAX_ITEM_PHOTOS) {
            throw PhotoRequestException(HttpStatus.CONFLICT)
        }
    }

    fun upload(validated: List<ValidatedPhoto>) = validated.forEach {
        storage.put(it.s3Key, it.contentType, it.bytes)
    }

    fun saveMetadata(ownerId: Long, itemId: Long, validated: List<ValidatedPhoto>): List<WardrobeItemPhotoResponse> =
        transaction.execute {
            val item = items.lockOwned(itemId, ownerId) ?: throw EntityNotFoundException()
            checkPhotoCapacity(photos.countByItemId(itemId), validated.size)
            validated.map {
                response(photos.create(requireNotNull(item.id), it.s3Key, it.contentType, it.bytes.size.toLong()))
            }
        }

    private fun response(photo: WardrobeItemPhoto) = WardrobeItemPhotoResponse(
        requireNotNull(photo.id), photo.itemId, photo.contentType, photo.sizeBytes, photo.createdAt,
    )
}
