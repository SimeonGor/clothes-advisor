package ru.itmo.clothesadvisor.model.rating

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal data class OutfitRatingHistoryId(
    val outfitId: Long,
    val stylistId: Long,
    val version: Long,
)

@Table("outfit_rating_history")
internal class OutfitRatingHistory
@PersistenceCreator
constructor(
    @Id @Embedded.Empty val id: OutfitRatingHistoryId,
    val vote: RatingVote,
    val modifiedAt: Instant,
) {
    constructor(
        rating: OutfitRating,
    ) : this(
        OutfitRatingHistoryId(rating.id.outfitId, rating.id.stylistId, rating.version),
        rating.vote,
        rating.modifiedAt,
    )
}
