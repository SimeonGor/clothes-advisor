package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.wardrobe.PhotoRequestException
import ru.itmo.clothesadvisor.service.wardrobe.UnknownWardrobeCategoryException
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException

@RestControllerAdvice(
    assignableTypes =
        [
            WardrobeItemController::class,
            WardrobeItemPhotoController::class,
            StylistWardrobeController::class,
            AdminWardrobeController::class,
        ],
)
internal class WardrobeItemExceptionHandler {
    @ExceptionHandler(PhotoRequestException::class)
    fun invalidPhoto(error: PhotoRequestException): ResponseEntity<Void> =
        ResponseEntity.status(error.status).build()

    @ExceptionHandler(PhotoStorageUnavailableException::class)
    fun storageUnavailable(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()

    @ExceptionHandler(UnknownWardrobeCategoryException::class)
    fun invalidCategory(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(
        OptimisticLockingFailureException::class,
        DataIntegrityViolationException::class,
    )
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.CONFLICT).build()
}
