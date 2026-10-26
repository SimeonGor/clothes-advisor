package ru.itmo.clothesadvisor.service.stylistrequest

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.stylistrequest.CancelStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CreateStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.StylistRequestResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_STYLIST_REQUEST
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequest
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestStatus
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestWeather
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.stylistrequest.StylistRequestRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.core.type.TypeReference

internal class UnknownRequestPrecipitationException : RuntimeException()

@Service
internal class StylistRequestService(
    private val requests: StylistRequestRepository,
    private val precipitation: PrecipitationTypeRepository,
    private val accounts: BlockingAccountClient,
    private val validator: Validator,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
) {
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readTransaction = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }

    fun create(user: CurrentUser, request: CreateStylistRequest, key: String? = null): StylistRequestResponse {
        requireRole(user, UserRole.USER)
        validate(request)
        accounts.requireStylistAccess(request.stylistId, user.id)
        return idempotency.execute(
            user.id, CREATE_STYLIST_REQUEST, key, listOf(user.id, request),
            object : TypeReference<StylistRequestResponse>() {},
        ) { completion ->
            completion.commit {
                writeTransaction.execute {
                    if (!precipitation.existsById(request.weather.precipitationTypeId)) {
                        throw UnknownRequestPrecipitationException()
                    }
                    val id = requests.create(user.id, request.stylistId, request.wishes.orEmpty())
                    requests.insertWeather(id, StylistRequestWeather(
                        request.weather.temperatureC, request.weather.precipitationTypeId, request.weather.windSpeedMps,
                    ))
                    response(find(id, user.id))
                }
            }
        }
    }

    fun get(user: CurrentUser, id: Long): StylistRequestResponse {
        requireRole(user, UserRole.USER)
        return readTransaction.execute { response(find(id, user.id)) }
    }

    fun list(user: CurrentUser, pageable: Pageable): Page<StylistRequestResponse> {
        requireRole(user, UserRole.USER)
        return listOwned(user.id, null, pageable)
    }

    fun getForClient(user: CurrentUser, ownerId: Long, id: Long): StylistRequestResponse {
        requireRole(user, UserRole.STYLIST)
        accounts.requireStylistAccess(user.id, ownerId)
        return readTransaction.execute { response(find(id, ownerId, user.id)) }
    }

    fun listForClient(user: CurrentUser, ownerId: Long, pageable: Pageable): Page<StylistRequestResponse> {
        requireRole(user, UserRole.STYLIST)
        accounts.requireStylistAccess(user.id, ownerId)
        return listOwned(ownerId, user.id, pageable)
    }

    fun cancel(user: CurrentUser, id: Long, request: CancelStylistRequest): StylistRequestResponse {
        requireRole(user, UserRole.USER)
        validate(request)
        return writeTransaction.execute {
            val previous = requests.findLocked(id, user.id) ?: throw EntityNotFoundException()
            if (previous.version != request.version) throw conflict(id)
            if (previous.status == StylistRequestStatus.CANCELLED) return@execute response(previous)
            if (previous.status != StylistRequestStatus.PENDING) throw conflict(id)
            if (requests.archive(id, user.id, request.version) != 1) throw conflict(id)
            if (requests.cancel(id, user.id, request.version) != 1) throw conflict(id)
            response(find(id, user.id))
        }
    }

    private fun listOwned(ownerId: Long, stylistId: Long?, pageable: Pageable): Page<StylistRequestResponse> =
        readTransaction.execute {
            PageImpl(
                requests.findPage(ownerId, stylistId, pageable.pageSize, pageable.offset).map(::response),
                pageable, requests.countOwned(ownerId, stylistId),
            )
        }

    private fun find(id: Long, ownerId: Long, stylistId: Long? = null) =
        requests.findOwned(id, ownerId, stylistId) ?: throw EntityNotFoundException()

    private fun requireRole(user: CurrentUser, role: UserRole) {
        if (user.role != role) throw AccessDeniedException("$role role required")
    }

    private fun validate(request: Any) {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
    }

    private fun conflict(id: Long) = OptimisticLockingFailureException("Stylist request $id version or state conflict")

    private fun response(row: StylistRequest) = StylistRequestResponse(
        row.id, row.ownerId, row.stylistId, row.wishes,
        OutfitWeatherDto(row.weather.temperatureC, row.weather.precipitationTypeId, row.weather.windSpeedMps),
        row.status, row.version, row.createdAt, row.modifiedAt,
    )
}
