package ru.itmo.clothesadvisor.model.rating

import java.time.Instant

internal data class OutfitRatingHistoryId(
    val outfitId: Long,
    val stylistId: Long,
    val version: Long,
)

internal class OutfitRatingHistory(rating: OutfitRating) {
    private val key = OutfitRatingHistoryId(rating.id.outfitId, rating.id.stylistId, rating.version)

    val vote: RatingVote = rating.vote

    val modifiedAt: Instant = rating.modifiedAt

    val id: OutfitRatingHistoryId get() = key
}
