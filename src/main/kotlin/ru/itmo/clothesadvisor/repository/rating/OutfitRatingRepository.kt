package ru.itmo.clothesadvisor.repository.rating

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.rating.OutfitRating
import ru.itmo.clothesadvisor.model.rating.OutfitRatingId
import ru.itmo.clothesadvisor.model.rating.RatingVote

internal data class RatingCounts(val outfitId: Long, val likes: Long, val dislikes: Long)

internal interface OutfitRatingRepository : Repository<OutfitRating, OutfitRatingId> {
    @Query(
        "SELECT * FROM outfit_rating WHERE outfit_id = :#{#id.outfitId} AND stylist_id = :#{#id.stylistId}",
    )
    fun findById(id: OutfitRatingId): OutfitRating?

    @Query(
        """
        INSERT INTO outfit_rating (outfit_id, stylist_id, vote, version)
        VALUES (:#{#id.outfitId}, :#{#id.stylistId}, :vote::rating_vote, :version)
        ON CONFLICT (outfit_id, stylist_id) DO NOTHING RETURNING *
    """,
    )
    fun insert(id: OutfitRatingId, vote: RatingVote, version: Long): OutfitRating?

    fun update(rating: OutfitRating, expectedVersion: Long): OutfitRating =
        updateRow(
            rating.id.outfitId,
            rating.id.stylistId,
            rating.vote,
            rating.version,
            expectedVersion,
        ) ?: throw OptimisticLockingFailureException("Rating version conflict")

    @Query(
        """
        UPDATE outfit_rating SET vote = :vote::rating_vote, version = :version, modified_at = CURRENT_TIMESTAMP
        WHERE outfit_id = :outfitId AND stylist_id = :stylistId AND version = :expectedVersion RETURNING *
    """,
    )
    fun updateRow(
        outfitId: Long,
        stylistId: Long,
        vote: RatingVote,
        version: Long,
        expectedVersion: Long,
    ): OutfitRating?

    fun delete(rating: OutfitRating) {
        if (deleteRow(rating.id.outfitId, rating.id.stylistId, rating.version) != 1) {
            throw OptimisticLockingFailureException("Rating version conflict")
        }
    }

    @Modifying
    @Query(
        "DELETE FROM outfit_rating WHERE outfit_id = :outfitId AND stylist_id = :stylistId AND version = :version",
    )
    fun deleteRow(outfitId: Long, stylistId: Long, version: Long): Int

    fun findAllByIdOutfitIdInAndIdStylistId(
        outfitIds: List<Long>,
        stylistId: Long,
    ): List<OutfitRating> = if (outfitIds.isEmpty()) emptyList() else findRows(outfitIds, stylistId)

    @Query(
        "SELECT * FROM outfit_rating WHERE stylist_id = :stylistId AND outfit_id IN (:outfitIds)",
    )
    fun findRows(outfitIds: List<Long>, stylistId: Long): List<OutfitRating>

    fun counts(outfitIds: List<Long>): List<RatingCounts> =
        if (outfitIds.isEmpty()) emptyList() else countRows(outfitIds)

    @Query(
        """
        SELECT outfit_id, count(*) FILTER (WHERE vote = 'LIKE') AS likes,
            count(*) FILTER (WHERE vote = 'DISLIKE') AS dislikes
        FROM outfit_rating WHERE outfit_id IN (:outfitIds) GROUP BY outfit_id
    """,
    )
    fun countRows(outfitIds: List<Long>): List<RatingCounts>
}
