package ru.itmo.clothesadvisor.controller.wardrobe

import jakarta.persistence.EntityNotFoundException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.wardrobe.UnknownWardrobeCategoryException

@RestControllerAdvice(assignableTypes = [WardrobeItemController::class])
internal class WardrobeItemExceptionHandler {
    @ExceptionHandler(EntityNotFoundException::class)
    fun notFound(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    @ExceptionHandler(UnknownWardrobeCategoryException::class)
    fun invalidCategory(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(OptimisticLockingFailureException::class, DataIntegrityViolationException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.CONFLICT).build()
}
