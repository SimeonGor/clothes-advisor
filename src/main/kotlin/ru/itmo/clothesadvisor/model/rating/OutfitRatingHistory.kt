package ru.itmo.clothesadvisor.model.rating

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
import org.springframework.data.domain.Persistable

@Embeddable
internal data class OutfitRatingHistoryId(
    @field:Column(name = "outfit_id", nullable = false) val outfitId: Long,
    @field:Column(name = "stylist_id", nullable = false) val stylistId: Long,
    @field:Column(nullable = false) val version: Long,
) : Serializable

@Entity
@Table(name = "outfit_rating_history")
internal class OutfitRatingHistory(rating: OutfitRating, archivedAt: Instant) : Persistable<OutfitRatingHistoryId> {
    @field:EmbeddedId
    private val key = OutfitRatingHistoryId(rating.id.outfitId, rating.id.stylistId, rating.version)

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(nullable = false, columnDefinition = "rating_vote")
    val vote: RatingVote = rating.vote

    @field:Column(name = "modified_at", nullable = false)
    val modifiedAt: Instant = rating.modifiedAt

    @field:Column(name = "archived_at", nullable = false)
    val archivedAt: Instant = archivedAt

    override fun getId(): OutfitRatingHistoryId = key
    override fun isNew(): Boolean = true
}
