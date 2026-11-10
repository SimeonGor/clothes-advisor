package ru.itmo.clothesadvisor.controller.ai

import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import reactor.core.publisher.Mono

internal interface AiJobValidationApi {
    @PostMapping("/internal/ai-jobs/{id}/validate-input")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun validate(@PathVariable id: UUID, @RequestBody body: ValidateAiJobInputRequest): Mono<Void>
}

internal data class ValidateAiJobInputRequest(val requestMonth: LocalDate)
