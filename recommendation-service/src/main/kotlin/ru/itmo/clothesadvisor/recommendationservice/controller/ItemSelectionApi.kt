package ru.itmo.clothesadvisor.recommendationservice.controller

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse

interface ItemSelectionApi {
    @PostMapping("/api/outfit-selections", consumes = ["application/json"])
    fun selectOutfit(@RequestBody body: Flux<DataBuffer>): Mono<ItemSelectionResponse>

    @PostMapping("/api/capsule-selections", consumes = ["application/json"])
    fun selectCapsule(@RequestBody body: Flux<DataBuffer>): Mono<ItemSelectionResponse>
}
