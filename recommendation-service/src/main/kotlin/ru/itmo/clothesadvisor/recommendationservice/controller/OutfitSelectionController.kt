package ru.itmo.clothesadvisor.recommendationservice.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionRequest
import ru.itmo.clothesadvisor.recommendationservice.service.OutfitSelectionService

@RestController
class OutfitSelectionController(private val service: OutfitSelectionService) {
    @PostMapping("/api/outfit-selections", consumes = ["application/json"])
    fun select(@RequestBody body: OutfitSelectionRequest) =
        Mono.fromCallable { service.select(body) }.subscribeOn(Schedulers.boundedElastic())

    @ExceptionHandler(AiOutfitException::class)
    fun selectionFailure(error: AiOutfitException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.status).body(mapOf("code" to error.failure.name.lowercase()))

    @ExceptionHandler(ServerWebInputException::class)
    fun invalidBody(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("code" to "invalid_request"))
}
