package ru.itmo.clothesadvisor.controller.preferences

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [StylePreferencesController::class])
internal class StylePreferencesExceptionHandler {
    @ExceptionHandler(
        OptimisticLockingFailureException::class,
        DataIntegrityViolationException::class,
    )
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()
}
