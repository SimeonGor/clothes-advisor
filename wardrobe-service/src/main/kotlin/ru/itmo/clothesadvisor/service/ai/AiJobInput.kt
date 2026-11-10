package ru.itmo.clothesadvisor.service.ai

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionStylePreferences
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionWeather

internal data class AiJobInput(
    val name: String,
    val sourceOutfitId: Long?,
    val precipitationTypeId: Long,
    val selection: SelectionInput,
)

internal data class SelectionInput(
    val weather: ItemSelectionWeather,
    val occasion: String?,
    val preferredStyle: String?,
    val stylePreferences: ItemSelectionStylePreferences?,
    val requiredItemIds: List<Long>,
    val candidates: List<SelectionCandidate>,
)

internal data class SelectionCandidate(
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

internal data class RecommendationRequested(
    val requestId: UUID,
    val requestMonth: LocalDate,
    val ownerId: Long,
    val acceptedAt: Instant,
    val startDeadline: Instant,
    val goal: AiSelectionGoal,
    val input: SelectionInput,
    val inputVersion: Int = 1,
)
