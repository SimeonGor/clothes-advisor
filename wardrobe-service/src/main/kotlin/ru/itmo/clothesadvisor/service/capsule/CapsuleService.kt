package ru.itmo.clothesadvisor.service.capsule

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.capsule.CapsuleResponse
import ru.itmo.clothesadvisor.dto.capsule.CreateCapsuleRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.capsule.Capsule
import ru.itmo.clothesadvisor.model.capsule.CapsuleItem
import ru.itmo.clothesadvisor.model.capsule.CapsuleItemId
import ru.itmo.clothesadvisor.model.capsule.CapsuleWeather
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_CAPSULE
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.capsule.CapsuleItemRepository
import ru.itmo.clothesadvisor.repository.capsule.CapsuleRepository
import ru.itmo.clothesadvisor.repository.capsule.CapsuleWeatherRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.core.type.TypeReference

internal class InvalidCapsuleRequestException : RuntimeException()

@Service
internal class CapsuleService(
    private val capsules: CapsuleRepository,
    private val composition: CapsuleItemRepository,
    private val weather: CapsuleWeatherRepository,
    private val wardrobe: WardrobeItemRepository,
    private val validator: Validator,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
) {
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readTransaction = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }

    fun create(user: CurrentUser, request: CreateCapsuleRequest, key: String? = null): CapsuleResponse {
        requireUser(user)
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
        if (request.itemIds.size !in 1..20 || request.itemIds.any { it == null || it <= 0 } ||
            request.itemIds.distinct().size != request.itemIds.size) {
            throw InvalidCapsuleRequestException()
        }
        val ids = request.itemIds.map { requireNotNull(it) }
        return idempotency.execute(
            user.id, CREATE_CAPSULE, key, listOf(user.id, request),
            object : TypeReference<CapsuleResponse>() {},
        ) { completion ->
            completion.commit {
                writeTransaction.execute {
                    if (wardrobe.countByOwnerIdAndIdIn(user.id, ids) != ids.size.toLong()) {
                        throw EntityNotFoundException()
                    }
                    val capsule = capsules.create(user.id, request.name)
                    saveComposition(capsule, ids)
                    toResponse(capsule, ids)
                }
            }
        }
    }

    fun createAi(ownerId: Long, request: CreateAiCapsuleRequest, ids: List<Long>, explanation: String): CapsuleResponse =
        writeTransaction.execute {
            val capsule = capsules.createAi(ownerId, request.name, request.occasion, request.preferredStyle, explanation)
            weather.insert(CapsuleWeather(capsule.id, request.weather.temperatureC,
                request.weather.precipitationTypeId, request.weather.windSpeedMps))
            saveComposition(capsule, ids)
            toResponse(capsule, ids, request.weather)
        }

    fun list(user: CurrentUser, pageable: Pageable): Page<CapsuleResponse> {
        requireUser(user)
        return readTransaction.execute {
            val rows = capsules.findPage(user.id, pageable.pageSize, pageable.offset)
            PageImpl(toResponses(rows), pageable, capsules.countByOwnerId(user.id))
        }
    }

    fun get(user: CurrentUser, id: Long): CapsuleResponse {
        requireUser(user)
        return readTransaction.execute {
            val capsule = capsules.findByIdAndOwnerId(id, user.id) ?: throw EntityNotFoundException()
            toResponses(listOf(capsule)).single()
        }
    }

    fun delete(user: CurrentUser, id: Long) {
        requireUser(user)
        writeTransaction.executeWithoutResult {
            val capsule = capsules.findLockedByIdAndOwnerId(id, user.id) ?: throw EntityNotFoundException()
            capsules.delete(capsule)
        }
    }

    private fun requireUser(user: CurrentUser) {
        if (user.role != UserRole.USER) throw AccessDeniedException("USER role required")
    }

    private fun toResponses(rows: List<Capsule>): List<CapsuleResponse> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val items = composition.findRows(ids).groupBy { it.id.capsuleId }
        val weatherByCapsule = weather.findAllByIdIn(ids).associateBy { it.id }
        return rows.map { capsule ->
            val weather = weatherByCapsule[capsule.id]?.let {
                OutfitWeatherDto(it.temperatureC, it.precipitationTypeId, it.windSpeedMps)
            }
            toResponse(capsule, items.getValue(capsule.id).map { it.id.wardrobeItemId }, weather)
        }
    }

    private fun saveComposition(capsule: Capsule, ids: List<Long>) =
        composition.insertAll(ids.mapIndexed { position, itemId ->
            CapsuleItem(CapsuleItemId(capsule.id, itemId), position)
        })

    private fun toResponse(capsule: Capsule, ids: List<Long>, weather: OutfitWeatherDto? = null) =
        CapsuleResponse(capsule.id, capsule.ownerId, capsule.name, ids, capsule.createdAt,
            capsule.source, weather, capsule.occasion, capsule.preferredStyle, capsule.explanation)
}
