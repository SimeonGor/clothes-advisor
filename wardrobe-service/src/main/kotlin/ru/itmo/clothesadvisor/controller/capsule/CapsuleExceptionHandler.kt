package ru.itmo.clothesadvisor.controller.capsule

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.capsule.InvalidCapsuleRequestException
import ru.itmo.clothesadvisor.controller.ai.AiCapsuleController
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException

@RestControllerAdvice(assignableTypes = [CapsuleController::class, AiCapsuleController::class])
internal class CapsuleExceptionHandler {
    @ExceptionHandler(AiSelectionException::class)
    fun aiFailure(error: AiSelectionException): ResponseEntity<Void> = ResponseEntity.status(error.status).build()

    @ExceptionHandler(PhotoStorageUnavailableException::class)
    fun storageUnavailable(): ResponseEntity<Void> = ResponseEntity.status(503).build()

    @ExceptionHandler(InvalidCapsuleRequestException::class)
    fun invalid(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()
}
