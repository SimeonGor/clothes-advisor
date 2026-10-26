package ru.itmo.clothesadvisor.client.recommendation

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionResponse

@FeignClient(
    name = "recommendation-service",
    configuration = [RecommendationClientConfiguration::class],
    fallbackFactory = RecommendationFallbackFactory::class,
)
interface RecommendationClient {
    @PostMapping("/api/outfit-selections", consumes = ["application/json"])
    fun select(@RequestBody request: ItemSelectionRequest): ItemSelectionResponse

    @PostMapping("/api/capsule-selections", consumes = ["application/json"])
    fun selectCapsule(@RequestBody request: ItemSelectionRequest): ItemSelectionResponse
}
