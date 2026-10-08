package ru.itmo.clothesadvisor.repository.rating

import java.sql.ResultSet
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.rating.OutfitRating
import ru.itmo.clothesadvisor.model.rating.OutfitRatingId
import ru.itmo.clothesadvisor.model.rating.RatingVote

internal data class RatingCounts(val outfitId: Long, val likes: Long, val dislikes: Long)

@Repository
internal class OutfitRatingRepository(private val jdbc: JdbcTemplate) {
    private fun map(row: ResultSet) = OutfitRating(OutfitRatingId(row.getLong("outfit_id"), row.getLong("stylist_id")),
        RatingVote.valueOf(row.getString("vote")), row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("modified_at").toInstant(), row.getLong("version"))

    fun findById(id: OutfitRatingId): OutfitRating? =
        jdbc.query("SELECT * FROM outfit_rating WHERE outfit_id = ? AND stylist_id = ?", { row, _ -> map(row) },
            id.outfitId, id.stylistId).singleOrNull()

    fun insert(id: OutfitRatingId, vote: RatingVote, version: Long): OutfitRating? = jdbc.query("""
        INSERT INTO outfit_rating (outfit_id, stylist_id, vote, version)
        VALUES (?, ?, ?::rating_vote, ?)
        ON CONFLICT (outfit_id, stylist_id) DO NOTHING RETURNING *
    """, { row, _ -> map(row) }, id.outfitId, id.stylistId, vote.name, version).singleOrNull()

    fun update(rating: OutfitRating, expectedVersion: Long): OutfitRating = jdbc.query("""
        UPDATE outfit_rating SET vote = ?::rating_vote, version = ?, modified_at = CURRENT_TIMESTAMP
        WHERE outfit_id = ? AND stylist_id = ? AND version = ? RETURNING *
    """, { row, _ -> map(row) }, rating.vote.name, rating.version, rating.id.outfitId,
        rating.id.stylistId, expectedVersion).singleOrNull()
        ?: throw OptimisticLockingFailureException("Rating version conflict")

    fun delete(rating: OutfitRating) {
        if (jdbc.update("DELETE FROM outfit_rating WHERE outfit_id = ? AND stylist_id = ? AND version = ?",
                rating.id.outfitId, rating.id.stylistId, rating.version) != 1)
            throw OptimisticLockingFailureException("Rating version conflict")
    }

    fun findAllByIdOutfitIdInAndIdStylistId(outfitIds: List<Long>, stylistId: Long): List<OutfitRating> {
        if (outfitIds.isEmpty()) return emptyList()
        return jdbc.query("SELECT * FROM outfit_rating WHERE stylist_id = ? AND outfit_id IN (${outfitIds.joinToString { "?" }})",
            { row, _ -> map(row) }, stylistId, *outfitIds.toTypedArray())
    }

    fun counts(outfitIds: List<Long>): List<RatingCounts> {
        if (outfitIds.isEmpty()) return emptyList()
        return jdbc.query("""
            SELECT outfit_id, count(*) FILTER (WHERE vote = 'LIKE') AS likes,
                count(*) FILTER (WHERE vote = 'DISLIKE') AS dislikes
            FROM outfit_rating WHERE outfit_id IN (${outfitIds.joinToString { "?" }}) GROUP BY outfit_id
        """, { row, _ -> RatingCounts(row.getLong("outfit_id"), row.getLong("likes"), row.getLong("dislikes")) },
            *outfitIds.toTypedArray())
    }
}
