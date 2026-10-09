package ru.itmo.clothesadvisor.model.rating

import com.fasterxml.jackson.annotation.JsonCreator
import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal enum class RatingVote {
    LIKE,
    DISLIKE;

    companion object {
        @JvmStatic @JsonCreator fun fromValue(value: String): RatingVote = valueOf(value)
    }
}

internal data class OutfitRatingId(
    val outfitId: Long,
    val stylistId: Long,
)

@Table("outfit_rating")
internal class OutfitRating(
    @Id @Embedded.Empty val id: OutfitRatingId,
    var vote: RatingVote,
    val createdAt: Instant,
    var modifiedAt: Instant = createdAt,
    var version: Long = 1,
)
