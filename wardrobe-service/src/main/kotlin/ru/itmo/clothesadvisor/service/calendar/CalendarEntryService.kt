package ru.itmo.clothesadvisor.service.calendar

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import java.time.LocalDate
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.calendar.CalendarEntryResponse
import ru.itmo.clothesadvisor.dto.calendar.CreateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.calendar.DeleteCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.calendar.UpdateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.calendar.CalendarEntry
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_CALENDAR_ENTRY
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.calendar.CalendarEntryRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.core.type.TypeReference

internal class InvalidCalendarRangeException : RuntimeException()

@Service
internal class CalendarEntryService(
    private val entries: CalendarEntryRepository,
    private val outfits: OutfitRepository,
    private val validator: Validator,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
) {
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readTransaction = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }

    fun create(user: CurrentUser, request: CreateCalendarEntryRequest, key: String? = null): CalendarEntryResponse {
        requireUser(user)
        validate(request)
        return idempotency.execute(
            user.id, CREATE_CALENDAR_ENTRY, key, listOf(user.id, request),
            object : TypeReference<CalendarEntryResponse>() {},
        ) { completion ->
            completion.commit {
                writeTransaction.execute {
                    requireOutfit(request.outfitId, user.id)
                    response(entries.create(user.id, request.date, request.outfitId, request.status))
                }
            }
        }
    }

    fun get(user: CurrentUser, id: Long): CalendarEntryResponse {
        requireUser(user)
        return response(find(id, user.id))
    }

    fun list(user: CurrentUser, from: LocalDate, to: LocalDate, pageable: Pageable): Page<CalendarEntryResponse> {
        requireUser(user)
        if (from > to) throw InvalidCalendarRangeException()
        return readTransaction.execute {
            PageImpl(
                entries.findPage(user.id, from, to, pageable.pageSize, pageable.offset).map(::response),
                pageable, entries.countOwned(user.id, from, to),
            )
        }
    }

    fun update(user: CurrentUser, id: Long, request: UpdateCalendarEntryRequest): CalendarEntryResponse {
        requireUser(user)
        validate(request)
        return writeTransaction.execute {
            val previous = locked(id, user.id, request.version)
            if (previous.calendarDate == request.date && previous.outfitId == request.outfitId && previous.status == request.status) {
                return@execute response(previous)
            }
            requireOutfit(request.outfitId, user.id)
            if (entries.archive(id, user.id, request.version) != 1) throw conflict(id)
            if (entries.update(id, user.id, request.version, request.date, request.outfitId, request.status) != 1) throw conflict(id)
            response(find(id, user.id))
        }
    }

    fun delete(user: CurrentUser, id: Long, version: Long) {
        requireUser(user)
        validate(DeleteCalendarEntryRequest(version))
        writeTransaction.executeWithoutResult {
            locked(id, user.id, version)
            if (entries.archive(id, user.id, version) != 1) throw conflict(id)
            if (entries.delete(id, user.id, version) != 1) throw conflict(id)
        }
    }

    private fun locked(id: Long, ownerId: Long, version: Long): CalendarEntry {
        val row = entries.findLocked(id, ownerId) ?: throw EntityNotFoundException()
        if (row.version != version) throw conflict(id)
        return row
    }

    private fun find(id: Long, ownerId: Long) = entries.findByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()

    private fun requireOutfit(id: Long, ownerId: Long) {
        if (outfits.findByIdAndOwnerId(id, ownerId) == null) throw EntityNotFoundException()
    }

    private fun requireUser(user: CurrentUser) {
        if (user.role != UserRole.USER) throw AccessDeniedException("USER role required")
    }

    private fun validate(request: Any) {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
    }

    private fun conflict(id: Long) = OptimisticLockingFailureException("Calendar entry $id version conflict")

    private fun response(row: CalendarEntry) = CalendarEntryResponse(
        row.id, row.ownerId, row.calendarDate, row.outfitId, row.status, row.version, row.createdAt, row.modifiedAt,
    )
}
