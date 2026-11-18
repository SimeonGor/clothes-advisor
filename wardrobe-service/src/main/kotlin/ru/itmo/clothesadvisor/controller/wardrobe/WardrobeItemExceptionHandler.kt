package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.model.wardrobe.PhotoRequestException
import ru.itmo.clothesadvisor.model.wardrobe.PhotoRequestFailure
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
        ResponseEntity.status(error.failure.httpStatus).build()

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

private val PhotoRequestFailure.httpStatus: HttpStatus
    get() = when (this) {
        PhotoRequestFailure.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
        PhotoRequestFailure.FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE
        PhotoRequestFailure.UNSUPPORTED_FORMAT -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
        PhotoRequestFailure.CAPACITY_EXCEEDED -> HttpStatus.CONFLICT
    }
