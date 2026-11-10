package ru.itmo.clothesadvisor.recommendationservice.request

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionStylePreferences
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionWeather
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal

// R2DBC encodes earlier values as -infinity. PostgreSQL excludes the upper endpoint.
private val firstFiniteTimestamp = Instant.parse("-4712-01-01T00:00:00Z")
private val endOfFiniteTimestamps = Instant.parse("+294277-01-01T00:00:00Z")

data class RecommendationRequested(
    val requestMonth: LocalDate,
    val requestId: UUID,
    val ownerId: Long,
    val inputVersion: Int,
    val goal: SelectionGoal,
    val acceptedAt: Instant,
    val startDeadline: Instant,
    val input: RecommendationInput,
) {
    internal fun validForAdmission(): Boolean =
        ownerId > 0 && acceptedAt >= firstFiniteTimestamp && startDeadline < endOfFiniteTimestamps &&
            startDeadline > acceptedAt && requestMonth == LocalDate.ofInstant(acceptedAt, ZoneOffset.UTC).withDayOfMonth(1) &&
            startDeadline.nano % 1000 == 0 && acceptedAt.nano % 1000 == 0 &&
            (goal != SelectionGoal.CAPSULE || input.requiredItemIds.isNullOrEmpty()) && input.validForAdmission()
}

data class RecommendationCandidate(
    val itemId: Long,
    val name: String,
    val categoryCode: String,
    val categoryName: String,
    val color: String,
    val material: String,
    val photoId: Long,
    val fileId: UUID,
    val contentType: String,
    val sizeBytes: Long,
)

data class RecommendationInput(
    val weather: ItemSelectionWeather,
    val candidates: List<RecommendationCandidate>,
    val occasion: String? = null,
    val preferredStyle: String? = null,
    val requiredItemIds: List<Long>? = null,
    val stylePreferences: ItemSelectionStylePreferences? = null,
) {
    internal fun validForAdmission(): Boolean {
        if (weather.windSpeedMps.signum() < 0 || candidates.size !in 1..20 ||
            (occasion?.length ?: 0) > 300 || (preferredStyle?.length ?: 0) > 300
        ) return false
        if (stylePreferences != null && listOf(
                stylePreferences.preferredColorsAndMaterials, stylePreferences.avoidances,
                stylePreferences.additionalWishes,
            ).any { it.length > 2000 }
        ) return false

        // A JSON array may contain null even when Kotlin declares a non-null element type.
        val supplied: List<RecommendationCandidate?> = candidates
        if (supplied.any { it == null || it.itemId <= 0 || it.photoId <= 0 ||
                it.contentType !in setOf("image/jpeg", "image/png") || it.sizeBytes !in 1..10_000_000
            }
        ) return false
        val candidateIds = candidates.map { it.itemId }.toSet()
        val required: List<Long?> = requiredItemIds.orEmpty()
        return candidateIds.size == candidates.size && required.distinct().size == required.size &&
            required.all { it != null && it in candidateIds }
    }

    /** The V1 identity rules are applied only to comparison, never to the original stored snapshot. */
    internal fun comparableV1() = copy(
        candidates = candidates.sortedBy { it.itemId },
        requiredItemIds = requiredItemIds.orEmpty().sorted(),
        weather = weather.copy(
            temperatureC = weather.temperatureC.stripTrailingZeros(),
            windSpeedMps = weather.windSpeedMps.stripTrailingZeros(),
        ),
    )
}

enum class RequestAcceptance {
    ACCEPTED, DUPLICATE, INPUT_CONFLICT, INVALID_INPUT, UNSUPPORTED_INPUT_VERSION,
    REQUEST_PERIOD_CLOSED, FUTURE_REQUEST_PERIOD,
}
