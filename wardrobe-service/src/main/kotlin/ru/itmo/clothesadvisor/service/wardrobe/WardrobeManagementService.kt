package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_WARDROBE_ITEM
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import tools.jackson.core.type.TypeReference

@Service
internal class WardrobeManagementService(
    private val items: WardrobeItemService,
    private val photos: WardrobeItemPhotoService,
    private val categories: WardrobeCategoryService,
    private val users: CurrentUserService,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun create(
        ownerId: Long,
        request: CreateWardrobeItemRequest,
        key: String? = null,
    ): WardrobeItemResponse {
        users.requireActive(ownerId)
        return idempotency.execute(
            ownerId, CREATE_WARDROBE_ITEM, key, listOf(ownerId, request),
            object : TypeReference<WardrobeItemResponse>() {},
        ) { completion ->
            completion.commit { items.create(ownerId, request) }
        }
    }

    fun createWithPhotos(
        ownerId: Long,
        request: CreateWardrobeItemRequest,
        files: List<ByteArray>,
        key: String? = null,
    ): WardrobeItemResponse {
        users.requireActive(ownerId)
        return idempotency.execute(
            ownerId, CREATE_WARDROBE_ITEM, key, listOf(ownerId, request),
            object : TypeReference<WardrobeItemResponse>() {}, files,
        ) { completion ->
            val validated = validatePhotos(files, allowEmpty = true)
            categories.getById(request.categoryId)
            photos.upload(validated)

            users.requireActive(ownerId)
            completion.commit {
                transaction.execute {
                    val created = items.create(ownerId, request)
                    photos.saveMetadata(ownerId, created.id, validated)
                    created
                }
            }
        }
    }
}
