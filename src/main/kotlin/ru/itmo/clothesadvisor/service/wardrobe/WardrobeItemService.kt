package ru.itmo.clothesadvisor.service.wardrobe

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistory
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemHistoryRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

@Service
@Transactional(readOnly = true)
internal class WardrobeItemService(
    private val items: WardrobeItemRepository,
    private val history: WardrobeItemHistoryRepository,
    private val categories: WardrobeCategoryService,
    private val users: AppUserService,
    private val validator: Validator,
) {
    @Transactional
    fun create(ownerId: Long, request: CreateWardrobeItemRequest): WardrobeItemResponse {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            throw ConstraintViolationException(violations)
        }

        val owner = users.findById(ownerId) ?: throw EntityNotFoundException()
        val category = categories.getById(request.categoryId)

        return toResponse(
            items.create(
                requireNotNull(owner.id),
                category,
                request.name,
                request.color,
                request.material,
            ),
        )
    }

    fun list(ownerId: Long, pageable: Pageable): List<WardrobeItemResponse> =
        items.findAllByOwnerIdOrderByIdAsc(ownerId, pageable).map(::toResponse)

    fun get(ownerId: Long, id: Long): WardrobeItemResponse = toResponse(findOwnedItem(ownerId, id))

    @Transactional
    fun update(ownerId: Long, id: Long, request: UpdateWardrobeItemRequest): WardrobeItemResponse {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            throw ConstraintViolationException(violations)
        }

        val item = findOwnedItem(ownerId, id)
        checkVersion(item, request.version)
        val category = categories.getById(request.categoryId)
        if (
            item.name == request.name &&
                item.category.id == request.categoryId &&
                item.color == request.color &&
                item.material == request.material
        ) {
            return toResponse(item)
        }

        val previous = WardrobeItemHistory(item)
        item.replace(category, request.name, request.color, request.material)
        val saved = items.update(item)
        history.insert(previous)

        return toResponse(saved)
    }

    @Transactional
    fun delete(ownerId: Long, id: Long, expectedVersion: Long) {
        val item = findOwnedItem(ownerId, id)
        checkVersion(item, expectedVersion)
        val previous = WardrobeItemHistory(item)
        items.delete(item)
        history.insert(previous)
    }

    private fun findOwnedItem(ownerId: Long, id: Long): WardrobeItem =
        items.findByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()

    private fun checkVersion(item: WardrobeItem, expectedVersion: Long) {
        if (item.version != expectedVersion) {
            throw OptimisticLockingFailureException("Wardrobe item ${item.id} version conflict")
        }
    }

    private fun toResponse(item: WardrobeItem) =
        WardrobeItemResponse(
            requireNotNull(item.id),
            item.name,
            requireNotNull(item.category.id),
            item.color,
            item.material,
            item.version,
            item.createdAt,
            item.modifiedAt,
        )
}
