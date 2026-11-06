package ru.itmo.clothesadvisor.controller.calendar

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.calendar.InvalidCalendarRangeException

@RestControllerAdvice(assignableTypes = [CalendarEntryController::class])
internal class CalendarEntryExceptionHandler {
    @ExceptionHandler(InvalidCalendarRangeException::class)
    fun invalid(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(OptimisticLockingFailureException::class, DataIntegrityViolationException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()
}
