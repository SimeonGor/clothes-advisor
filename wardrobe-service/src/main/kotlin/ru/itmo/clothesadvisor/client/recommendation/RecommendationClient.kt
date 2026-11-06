package ru.itmo.clothesadvisor.client.recommendation

import feign.Headers
import feign.RequestLine
import org.springframework.core.io.buffer.DataBuffer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionResponse

interface RecommendationClient {
    fun select(request: ItemSelectionRequest): ItemSelectionResponse

    fun selectCapsule(request: ItemSelectionRequest): ItemSelectionResponse
}

internal interface StreamingRecommendationClient {
    @RequestLine("POST /api/outfit-selections")
    @Headers("Content-Type: application/json")
    fun select(body: Flux<DataBuffer>): Mono<ItemSelectionResponse>

    @RequestLine("POST /api/capsule-selections")
    @Headers("Content-Type: application/json")
    fun selectCapsule(body: Flux<DataBuffer>): Mono<ItemSelectionResponse>
}
