package ru.itmo.clothesadvisor.service.wardrobe

import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import org.springframework.data.domain.Pageable
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistory
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemHistoryRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeCategoryRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

@Service
@Transactional(readOnly = true)
internal class WardrobeItemService(
    private val items: WardrobeItemRepository,
    private val history: WardrobeItemHistoryRepository,
    private val categories: WardrobeCategoryRepository,
    private val users: AppUserService,
    private val clock: Clock,
) {
    @Transactional
    fun create(ownerId: Long, request: CreateWardrobeItemRequest): WardrobeItemResponse {
        val owner = users.findById(ownerId) ?: throw EntityNotFoundException()
        val category = category(request.categoryId)
        return response(items.save(WardrobeItem(
            owner, category, request.name, request.color, request.material, clock.instant(),
        )))
    }

    fun list(ownerId: Long, pageable: Pageable): List<WardrobeItemResponse> =
        items.findAllByOwnerIdOrderByIdAsc(ownerId, pageable).map(::response)

    fun get(ownerId: Long, id: Long): WardrobeItemResponse = response(owned(ownerId, id))

    @Transactional
    fun update(ownerId: Long, id: Long, request: UpdateWardrobeItemRequest): WardrobeItemResponse {
        val item = owned(ownerId, id)
        checkVersion(item, request.version)
        val category = category(request.categoryId)
        if (item.name == request.name && item.category.id == request.categoryId &&
            item.color == request.color && item.material == request.material
        ) return response(item)

        val now = clock.instant()
        val previous = WardrobeItemHistory(item, now)
        item.replace(category, request.name, request.color, request.material, now)
        items.flush()
        history.save(previous)
        return response(item)
    }

    @Transactional
    fun delete(ownerId: Long, id: Long, expectedVersion: Long) {
        val item = owned(ownerId, id)
        checkVersion(item, expectedVersion)
        val previous = WardrobeItemHistory(item, clock.instant())
        items.delete(item)
        items.flush()
        history.save(previous)
    }

    private fun owned(ownerId: Long, id: Long): WardrobeItem =
        items.findByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()

    private fun category(id: Long) = categories.findById(id) ?: throw UnknownWardrobeCategoryException()

    private fun checkVersion(item: WardrobeItem, expectedVersion: Long) {
        if (item.version != expectedVersion) {
            throw ObjectOptimisticLockingFailureException(WardrobeItem::class.java, requireNotNull(item.id))
        }
    }

    private fun response(item: WardrobeItem) = WardrobeItemResponse(
        requireNotNull(item.id), item.name, requireNotNull(item.category.id),
        item.color, item.material, item.version, item.createdAt, item.modifiedAt,
    )
}

internal class UnknownWardrobeCategoryException : RuntimeException()
