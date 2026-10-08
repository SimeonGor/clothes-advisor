package ru.itmo.clothesadvisor.controller

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import jakarta.validation.ConstraintViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import ru.itmo.clothesadvisor.model.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import ru.itmo.clothesadvisor.config.InvalidCurrentUserException
import ru.itmo.clothesadvisor.dto.ApiErrorResponse

@RestControllerAdvice
internal class ApiExceptionHandler {
    @ExceptionHandler(EntityNotFoundException::class)
    fun notFound(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge(): ResponseEntity<Void> = ResponseEntity.status(413).build()

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(403).body(ApiErrorResponse("forbidden"))

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        InvalidCurrentUserException::class,
        ConstraintViolationException::class,
    )
    fun invalidRequest(): ResponseEntity<ApiErrorResponse> = ResponseEntity.badRequest().body(ApiErrorResponse("invalid_request"))
}
