package ru.itmo.clothesadvisor.repository.rating

import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistory
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistoryId

internal interface RatingHistoryRow {
    val stylistId: Long
    val vote: String
    val version: Long
    val modifiedAt: Instant
    val archivedAt: Instant?
}

internal interface OutfitRatingHistoryRepository : Repository<OutfitRatingHistory, OutfitRatingHistoryId> {
    fun save(history: OutfitRatingHistory): OutfitRatingHistory
    fun flush()

    @Query(value = """
        SELECT max(version) FROM outfit_rating_history WHERE outfit_id = :outfitId AND stylist_id = :stylistId
    """, nativeQuery = true)
    fun maxVersion(outfitId: Long, stylistId: Long): Long?

    @Modifying
    @Query(value = """
        INSERT INTO outfit_rating_history (outfit_id, stylist_id, version, vote, modified_at, archived_at)
        SELECT outfit_id, stylist_id, version, vote, modified_at, :archivedAt
        FROM outfit_rating WHERE outfit_id = :outfitId
    """, nativeQuery = true)
    fun archiveCurrent(outfitId: Long, archivedAt: Instant): Int

    @Query(value = """
        SELECT stylist_id AS "stylistId", vote::text AS vote, version,
            modified_at AS "modifiedAt", archived_at AS "archivedAt"
        FROM (
            SELECT stylist_id, vote, version, modified_at, archived_at
            FROM outfit_rating_history WHERE outfit_id = :outfitId
            UNION ALL
            SELECT stylist_id, vote, version, modified_at, NULL::timestamptz AS archived_at
            FROM outfit_rating WHERE outfit_id = :outfitId
        ) ratings ORDER BY modified_at DESC, stylist_id DESC, version DESC
    """, countQuery = """
        SELECT (SELECT count(*) FROM outfit_rating_history WHERE outfit_id = :outfitId)
             + (SELECT count(*) FROM outfit_rating WHERE outfit_id = :outfitId)
    """, nativeQuery = true)
    fun timeline(outfitId: Long, pageable: Pageable): Page<RatingHistoryRow>
}
