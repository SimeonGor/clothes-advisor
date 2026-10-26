package ru.itmo.clothesadvisor.controller.stylistrequest

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.stylistrequest.UnknownRequestPrecipitationException

@RestControllerAdvice(assignableTypes = [StylistRequestController::class, StylistClientRequestController::class])
internal class StylistRequestExceptionHandler {
    @ExceptionHandler(UnknownRequestPrecipitationException::class)
    fun invalid(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(OptimisticLockingFailureException::class, DataIntegrityViolationException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()
}
