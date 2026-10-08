package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse

@Service
internal class WardrobeManagementService(
    private val items: WardrobeItemService,
    private val photos: WardrobeItemPhotoService,
    private val categories: WardrobeCategoryService,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun createWithPhotos(ownerId: Long, request: CreateWardrobeItemRequest, files: List<MultipartFile>): WardrobeItemResponse {
        val validated = validatePhotos(files, allowEmpty = true)
        categories.getById(request.categoryId)
        photos.upload(validated)
        return transaction.execute {
            val created = items.create(ownerId, request)
            photos.saveMetadata(ownerId, created.id, validated)
            created
        }
    }
}
