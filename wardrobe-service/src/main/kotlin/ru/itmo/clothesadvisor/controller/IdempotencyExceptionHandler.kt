package ru.itmo.clothesadvisor.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyException

@RestControllerAdvice
internal class IdempotencyExceptionHandler {
    @ExceptionHandler(IdempotencyException::class)
    fun handle(error: IdempotencyException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(error.status).body(ApiErrorResponse(error.code))
}
