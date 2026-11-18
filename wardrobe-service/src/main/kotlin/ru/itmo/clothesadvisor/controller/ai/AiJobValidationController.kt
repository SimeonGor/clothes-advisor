package ru.itmo.clothesadvisor.controller.ai

import java.util.UUID
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.service.ai.AiJobValidation
import ru.itmo.clothesadvisor.service.ai.JobInputConflict
import ru.itmo.clothesadvisor.service.blockingCall

@RestController
internal class AiJobValidationController(private val validation: AiJobValidation) : AiJobValidationApi {
    override fun validate(id: UUID, body: ValidateAiJobInputRequest) = blockingCall {
        validation.validate(id, body.requestMonth)
    }.then()

    @ExceptionHandler(JobInputConflict::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict() = Unit

    @ExceptionHandler(EntityNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun missing() = Unit

    @ExceptionHandler(AccessDeniedException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun denied() = Unit

    @ExceptionHandler(AccountUnavailableException::class, DataAccessException::class, TransactionException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun unavailable() = Unit
}
