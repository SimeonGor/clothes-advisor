package ru.itmo.clothesadvisor.dto.rating

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive
import java.time.Instant
import ru.itmo.clothesadvisor.model.rating.RatingVote

internal data class CreateRatingRequest(val vote: RatingVote)
internal data class UpdateRatingRequest(val vote: RatingVote, @field:Positive val version: Long)
internal data class RatingResponse(val vote: RatingVote, val version: Long)
internal data class RatingHistoryResponse(
    val stylistId: Long,
    val vote: RatingVote,
    val version: Long,
    val modifiedAt: Instant,
    @field:Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED, description = "Время архивации; null для текущего состояния")
    val archivedAt: Instant?,
)
