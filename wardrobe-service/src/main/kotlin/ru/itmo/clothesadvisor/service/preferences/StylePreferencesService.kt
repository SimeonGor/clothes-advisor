package ru.itmo.clothesadvisor.service.preferences

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.dto.preferences.PutStylePreferencesRequest
import ru.itmo.clothesadvisor.dto.preferences.StylePreferencesResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.preferences.StylePreferences
import ru.itmo.clothesadvisor.model.preferences.StylePreferencesHistory
import ru.itmo.clothesadvisor.model.preferences.StylePreferencesHistoryId
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.preferences.StylePreferencesHistoryRepository
import ru.itmo.clothesadvisor.repository.preferences.StylePreferencesRepository

@Service
internal class StylePreferencesService(
    private val preferences: StylePreferencesRepository,
    private val history: StylePreferencesHistoryRepository,
    private val accounts: BlockingAccountClient,
    private val validator: Validator,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun get(user: CurrentUser): StylePreferencesResponse {
        requireOwner(user)
        return transaction.execute { toResponse(find(user.id)) }
    }

    fun getForClient(stylistId: Long, ownerId: Long): StylePreferencesResponse {
        accounts.requireStylistAccess(stylistId, ownerId)
        return transaction.execute { toResponse(find(ownerId)) }
    }

    fun put(user: CurrentUser, request: PutStylePreferencesRequest): StylePreferencesResponse {
        requireOwner(user)
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)

        val colors = request.preferredColorsAndMaterials.orEmpty()
        val avoidances = request.avoidances.orEmpty()
        val wishes = request.additionalWishes.orEmpty()
        return transaction.execute {
            if (request.version == null) {
                val created = preferences.create(user.id, colors, avoidances, wishes)
                    ?: throw conflict(user.id)
                return@execute toResponse(created)
            }

            val previous = preferences.findByOwnerId(user.id) ?: throw conflict(user.id)
            if (previous.version != request.version) throw conflict(user.id)
            val changed = previous.copy(
                preferredColorsAndMaterials = colors,
                avoidances = avoidances,
                additionalWishes = wishes,
            )
            if (changed == previous) return@execute toResponse(previous)

            val saved = preferences.update(changed) ?: throw conflict(user.id)
            history.insert(
                StylePreferencesHistory(
                    StylePreferencesHistoryId(previous.ownerId, previous.version),
                    previous.preferredColorsAndMaterials,
                    previous.avoidances,
                    previous.additionalWishes,
                    previous.createdAt,
                    previous.modifiedAt,
                ),
            )
            toResponse(saved)
        }
    }

    private fun requireOwner(user: CurrentUser) {
        if (user.role != UserRole.USER) throw AccessDeniedException("User role required")
    }

    private fun find(ownerId: Long): StylePreferences =
        preferences.findByOwnerId(ownerId) ?: throw EntityNotFoundException()

    private fun conflict(ownerId: Long) =
        OptimisticLockingFailureException("Style preferences for $ownerId version conflict")

    private fun toResponse(row: StylePreferences) = StylePreferencesResponse(
        row.preferredColorsAndMaterials,
        row.avoidances,
        row.additionalWishes,
        row.version,
        row.createdAt,
        row.modifiedAt,
    )
}
