package ru.itmo.clothesadvisor.controller

import jakarta.validation.ConstraintViolationException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.server.ServerWebInputException
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException

@RestControllerAdvice
internal class ApiExceptionHandler {
    @ExceptionHandler(AccountUnavailableException::class)
    fun accountUnavailable(): ResponseEntity<Void> = ResponseEntity.status(503).build()

    @ExceptionHandler(EntityNotFoundException::class)
    fun notFound(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    @ExceptionHandler(DataBufferLimitException::class)
    fun uploadTooLarge(): ResponseEntity<Void> = ResponseEntity.status(413).build()

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(403).body(ApiErrorResponse("forbidden"))

    @ExceptionHandler(
        WebExchangeBindException::class,
        ServerWebInputException::class,
        HandlerMethodValidationException::class,
        ConstraintViolationException::class,
    )
    fun invalidRequest(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse("invalid_request"))
}
