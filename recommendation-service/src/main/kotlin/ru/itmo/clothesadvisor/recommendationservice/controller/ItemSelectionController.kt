package ru.itmo.clothesadvisor.recommendationservice.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.client.ai.AiSelectionException
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionService

@RestController
class ItemSelectionController(private val service: ItemSelectionService) {
    @PostMapping("/api/outfit-selections", consumes = ["application/json"])
    fun selectOutfit(@RequestBody body: ItemSelectionRequest) =
        Mono.fromCallable { service.selectOutfit(body) }.subscribeOn(Schedulers.boundedElastic())

    @PostMapping("/api/capsule-selections", consumes = ["application/json"])
    fun selectCapsule(@RequestBody body: ItemSelectionRequest) =
        Mono.fromCallable { service.selectCapsule(body) }.subscribeOn(Schedulers.boundedElastic())

    @ExceptionHandler(AiSelectionException::class)
    fun selectionFailure(error: AiSelectionException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.status).body(mapOf("code" to error.failure.name.lowercase()))

    @ExceptionHandler(ServerWebInputException::class)
    fun invalidBody(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("code" to "invalid_request"))
}
