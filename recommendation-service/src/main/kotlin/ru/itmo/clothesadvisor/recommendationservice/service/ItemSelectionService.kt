package ru.itmo.clothesadvisor.recommendationservice.service

import java.util.Base64
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.client.ai.AiSelectionException
import ru.itmo.clothesadvisor.client.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.client.ai.SelectionGoal
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionCandidate
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse

@Service
class ItemSelectionService(private val provider: OpenAiSelectionClient) {
    fun selectOutfit(request: ItemSelectionRequest): ItemSelectionResponse = select(request, SelectionGoal.OUTFIT)

    fun selectCapsule(request: ItemSelectionRequest): ItemSelectionResponse {
        if (request.requiredItemIds != null) invalidRequest()
        return select(request, SelectionGoal.CAPSULE)
    }

    private fun select(request: ItemSelectionRequest, goal: SelectionGoal): ItemSelectionResponse {
        if ((request.occasion?.length ?: 0) > 300 || (request.preferredStyle?.length ?: 0) > 300) {
            invalidRequest()
        }
        request.stylePreferences?.let { preferences ->
            if (preferences.preferredColorsAndMaterials.length > 2000 ||
                preferences.avoidances.length > 2000 || preferences.additionalWishes.length > 2000
            ) invalidRequest()
        }
        val candidates: List<ItemSelectionCandidate?> = request.candidates
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
        request.requiredItemIds?.let { required ->
            if (required.distinct().size != required.size || required.any { it == null || it <= 0 || it !in allowed }) {
                invalidRequest()
            }
        }
        return try {
            provider.select(goal, request)
        } catch (error: AiSelectionException) {
            if (error.failure != AiSelectionFailure.NO_SELECTION) throw error
            throw AiSelectionException(when (goal) {
                SelectionGoal.OUTFIT -> AiSelectionFailure.NO_OUTFIT
                SelectionGoal.CAPSULE -> AiSelectionFailure.NO_CAPSULE
            })
        }
    }

    private fun invalidRequest(): Nothing = throw AiSelectionException(AiSelectionFailure.INVALID_REQUEST)
}
