package ru.itmo.clothesadvisor.accountservice.controller.user

import jakarta.validation.ConstraintViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.server.ServerWebInputException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.service.user.AdminUserConflictException
import ru.itmo.clothesadvisor.accountservice.service.user.AdminUserForbiddenException

internal data class ApiErrorResponse(val error: String)

@RestControllerAdvice(assignableTypes = [AdminUserController::class])
internal class AdminUserExceptionHandler {
    @ExceptionHandler(EntityNotFoundException::class)
    fun notFound(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    @ExceptionHandler(AdminUserConflictException::class, OptimisticLockingFailureException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()

    @ExceptionHandler(AdminUserForbiddenException::class)
    fun forbidden(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(403).body(ApiErrorResponse("forbidden"))

    @ExceptionHandler(
        InvalidAdminUserRequestException::class,
        ServerWebInputException::class,
        HandlerMethodValidationException::class,
        ConstraintViolationException::class,
    )
    fun invalidRequest(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse("invalid_request"))
}
