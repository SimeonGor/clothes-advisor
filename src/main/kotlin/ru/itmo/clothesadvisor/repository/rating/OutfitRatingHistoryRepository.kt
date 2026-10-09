package ru.itmo.clothesadvisor.repository.rating

import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistory
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistoryId
import ru.itmo.clothesadvisor.model.rating.RatingVote

internal data class RatingTimelineEntry(
    val stylistId: Long, val vote: RatingVote, val version: Long, val modifiedAt: Instant, val archivedAt: Instant?,
)

internal interface OutfitRatingHistoryRepository : Repository<OutfitRatingHistory, OutfitRatingHistoryId> {
    fun insert(history: OutfitRatingHistory) = insert(
        history.id.outfitId, history.id.stylistId, history.id.version, history.vote, history.modifiedAt,
    )

    @Modifying
    @Query("""
        INSERT INTO outfit_rating_history (outfit_id, stylist_id, version, vote, modified_at)
        VALUES (:outfitId, :stylistId, :version, :vote::rating_vote, :modifiedAt)
    """)
    fun insert(outfitId: Long, stylistId: Long, version: Long, vote: RatingVote, modifiedAt: Instant)

    @Query("SELECT max(version) FROM outfit_rating_history WHERE outfit_id = :outfitId AND stylist_id = :stylistId")
    fun maxVersion(outfitId: Long, stylistId: Long): Long?

    @Modifying
    @Query("""
        INSERT INTO outfit_rating_history (outfit_id, stylist_id, version, vote, modified_at)
        SELECT outfit_id, stylist_id, version, vote, modified_at
        FROM outfit_rating WHERE outfit_id = :outfitId
    """)
    fun archiveCurrent(outfitId: Long): Int

    fun timeline(outfitId: Long, pageable: Pageable): Page<RatingTimelineEntry> =
        PageImpl(findTimelineRows(outfitId, pageable.pageSize, pageable.offset), pageable, countTimelineRows(outfitId))

    @Query("""
        SELECT * FROM (
            SELECT stylist_id, vote, version, modified_at, archived_at
            FROM outfit_rating_history WHERE outfit_id = :outfitId
            UNION ALL
            SELECT stylist_id, vote, version, modified_at, NULL::timestamptz AS archived_at
            FROM outfit_rating WHERE outfit_id = :outfitId
        ) ratings ORDER BY modified_at DESC, stylist_id DESC, version DESC LIMIT :limit OFFSET :offset
    """)
    fun findTimelineRows(outfitId: Long, limit: Int, offset: Long): List<RatingTimelineEntry>

    @Query("""
        SELECT (SELECT count(*) FROM outfit_rating_history WHERE outfit_id = :outfitId)
             + (SELECT count(*) FROM outfit_rating WHERE outfit_id = :outfitId)
    """)
    fun countTimelineRows(outfitId: Long): Long
}
