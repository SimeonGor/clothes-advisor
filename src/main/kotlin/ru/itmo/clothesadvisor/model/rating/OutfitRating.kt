package ru.itmo.clothesadvisor.model.rating

import com.fasterxml.jackson.annotation.JsonCreator
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

internal enum class RatingVote {
    LIKE, DISLIKE;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): RatingVote = valueOf(value)
    }
}

@Embeddable
internal data class OutfitRatingId(
    @field:Column(name = "outfit_id", nullable = false) val outfitId: Long,
    @field:Column(name = "stylist_id", nullable = false) val stylistId: Long,
) : Serializable

@Entity
@Table(name = "outfit_rating")
internal class OutfitRating(
    @field:EmbeddedId val id: OutfitRatingId,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(nullable = false, columnDefinition = "rating_vote")
    var vote: RatingVote,
    @field:Column(name = "created_at", nullable = false) val createdAt: Instant,
    @field:Column(name = "modified_at", nullable = false) var modifiedAt: Instant = createdAt,
    @field:Column(nullable = false) var version: Long = 1,
)
