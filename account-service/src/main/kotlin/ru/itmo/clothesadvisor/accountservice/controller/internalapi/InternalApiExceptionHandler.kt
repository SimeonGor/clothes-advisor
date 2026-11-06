package ru.itmo.clothesadvisor.accountservice.controller.internalapi

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException
import ru.itmo.clothesadvisor.accountservice.dto.InternalApiErrorResponse
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException

@RestControllerAdvice(assignableTypes = [InternalAccountController::class])
internal class InternalApiExceptionHandler {
    @ExceptionHandler(EntityNotFoundException::class)
    fun notFound(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(): ResponseEntity<InternalApiErrorResponse> =
        ResponseEntity.status(403).body(InternalApiErrorResponse("forbidden"))

    @ExceptionHandler(ServerWebInputException::class)
    fun invalidRequest(): ResponseEntity<InternalApiErrorResponse> =
        ResponseEntity.badRequest().body(InternalApiErrorResponse("invalid_request"))
}
