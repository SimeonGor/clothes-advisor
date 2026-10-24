package ru.itmo.clothesadvisor.recommendationservice.service

import java.util.Base64
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.client.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionResponse

@Service
class OutfitSelectionService(private val provider: OpenAiOutfitClient) {
    fun select(request: OutfitSelectionRequest): OutfitSelectionResponse {
        if ((request.occasion?.length ?: 0) > 300 || (request.preferredStyle?.length ?: 0) > 300) {
            invalidRequest()
        }
        val candidates: List<OutfitSelectionCandidate?> = request.candidates
        if (candidates.size !in 1..20 || request.weather.windSpeedMps.signum() < 0) {
            invalidRequest()
        }
        candidates.forEach { candidate ->
            if (candidate == null || candidate.itemId <= 0 ||
                candidate.contentType !in setOf("image/jpeg", "image/png")
            ) invalidRequest()
            val image = candidate.imageBase64
            if (image.isEmpty() || image.length > 13_333_336 || image.length % 4 != 0) {
                invalidRequest()
            }
            val bytes = try {
                Base64.getDecoder().decode(image)
            } catch (_: IllegalArgumentException) {
                invalidRequest()
            }
            if (bytes.size !in 1..10_000_000 ||
                Base64.getEncoder().encodeToString(bytes) != image
            ) invalidRequest()
        }
        val allowed = request.candidates.map { it.itemId }.toSet()
        if (allowed.size != request.candidates.size) invalidRequest()
        return provider.select(request)
    }

    private fun invalidRequest(): Nothing = throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
}
