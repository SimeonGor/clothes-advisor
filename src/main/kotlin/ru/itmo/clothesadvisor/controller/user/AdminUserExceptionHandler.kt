package ru.itmo.clothesadvisor.controller.user

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.itmo.clothesadvisor.service.user.AdminUserConflictException

@RestControllerAdvice(assignableTypes = [AdminUserController::class])
internal class AdminUserExceptionHandler {
    @ExceptionHandler(AdminUserConflictException::class, OptimisticLockingFailureException::class)
    fun conflict(): ResponseEntity<Void> = ResponseEntity.status(409).build()
}
