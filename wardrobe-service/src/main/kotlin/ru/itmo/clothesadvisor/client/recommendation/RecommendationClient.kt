package ru.itmo.clothesadvisor.client.recommendation

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionResponse

@FeignClient(
    name = "recommendation-service",
    configuration = [RecommendationClientConfiguration::class],
    fallbackFactory = RecommendationFallbackFactory::class,
)
interface RecommendationClient {
    @PostMapping("/api/outfit-selections", consumes = ["application/json"])
    fun select(@RequestBody request: OutfitSelectionRequest): OutfitSelectionResponse
}
