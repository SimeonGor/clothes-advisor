package ru.itmo.clothesadvisor.controller.capsule

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.capsule.InvalidCapsuleRequestException

@RestControllerAdvice(assignableTypes = [CapsuleController::class])
internal class CapsuleExceptionHandler {
    @ExceptionHandler(InvalidCapsuleRequestException::class)
    fun invalid(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()
}
