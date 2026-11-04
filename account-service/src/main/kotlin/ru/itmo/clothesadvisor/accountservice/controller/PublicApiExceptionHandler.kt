package ru.itmo.clothesadvisor.accountservice.controller

import jakarta.validation.ConstraintViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.server.ServerWebInputException
import ru.itmo.clothesadvisor.accountservice.controller.access.StylistAccessController
import ru.itmo.clothesadvisor.accountservice.controller.access.StylistClientController
import ru.itmo.clothesadvisor.accountservice.controller.user.AdminUserController
import ru.itmo.clothesadvisor.accountservice.controller.user.StylistDirectoryController
import ru.itmo.clothesadvisor.accountservice.controller.auth.AuthController
import ru.itmo.clothesadvisor.accountservice.config.AccountAuthenticationUnavailableException
import ru.itmo.clothesadvisor.accountservice.service.auth.InvalidCredentialsException
import ru.itmo.clothesadvisor.accountservice.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.service.user.AdminUserConflictException

@RestControllerAdvice(assignableTypes = [
    AdminUserController::class, StylistAccessController::class, StylistClientController::class,
    AuthController::class, StylistDirectoryController::class,
])
internal class PublicApiExceptionHandler {
    @ExceptionHandler(InvalidCredentialsException::class)
    fun unauthorized(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(401).header("WWW-Authenticate", "Bearer")
            .header("Cache-Control", "no-store").body(ApiErrorResponse("unauthorized"))

    @ExceptionHandler(AccountAuthenticationUnavailableException::class)
    fun unavailable(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(503).body(ApiErrorResponse("service_unavailable"))

    @ExceptionHandler(EntityNotFoundException::class)
    fun notFound(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    @ExceptionHandler(AdminUserConflictException::class, OptimisticLockingFailureException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(403).body(ApiErrorResponse("forbidden"))

    @ExceptionHandler(
        InvalidRequestException::class,
        ServerWebInputException::class,
        HandlerMethodValidationException::class,
        ConstraintViolationException::class,
    )
    fun invalidRequest(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse("invalid_request"))
}
