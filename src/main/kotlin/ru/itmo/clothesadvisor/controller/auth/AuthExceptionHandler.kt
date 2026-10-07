package ru.itmo.clothesadvisor.controller.auth

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.security.auth.ApiSecurityErrors
import ru.itmo.clothesadvisor.dto.auth.AuthError

@RestControllerAdvice
internal class AuthExceptionHandler {
    @ExceptionHandler(AuthenticationException::class)
    fun unauthorized(response: HttpServletResponse) = ApiSecurityErrors.unauthorized(response)

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(response: HttpServletResponse) = ApiSecurityErrors.forbidden(response)

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        InvalidLoginRequestException::class,
    )
    fun invalidRequest(): ResponseEntity<AuthError> = ResponseEntity.badRequest().body(AuthError("invalid_request"))
}
