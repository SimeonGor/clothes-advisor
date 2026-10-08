package ru.itmo.clothesadvisor.controller.outfit

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.outfit.InvalidOutfitRequestException
import ru.itmo.clothesadvisor.service.rating.RatingConflictException

@RestControllerAdvice(assignableTypes = [OutfitController::class, StylistOutfitController::class, AdminOutfitController::class])
internal class OutfitExceptionHandler {
    @ExceptionHandler(InvalidOutfitRequestException::class)
    fun invalidRequest(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(DataIntegrityViolationException::class, OptimisticLockingFailureException::class, RatingConflictException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()
}
