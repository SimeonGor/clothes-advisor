package ru.itmo.clothesadvisor.repository.rating

import java.sql.Timestamp
import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistory

internal data class RatingHistoryRow(
    val stylistId: Long, val vote: String, val version: Long, val modifiedAt: Instant, val archivedAt: Instant?,
)

@Repository
internal class OutfitRatingHistoryRepository(private val jdbc: JdbcTemplate) {
    fun save(history: OutfitRatingHistory) {
        jdbc.update("""
            INSERT INTO outfit_rating_history (outfit_id, stylist_id, version, vote, modified_at)
            VALUES (?, ?, ?, ?::rating_vote, ?)
        """, history.id.outfitId, history.id.stylistId, history.id.version, history.vote.name,
            Timestamp.from(history.modifiedAt))
    }

    fun maxVersion(outfitId: Long, stylistId: Long): Long? =
        jdbc.queryForObject("SELECT max(version) FROM outfit_rating_history WHERE outfit_id = ? AND stylist_id = ?",
            Long::class.java, outfitId, stylistId)

    fun archiveCurrent(outfitId: Long): Int = jdbc.update("""
        INSERT INTO outfit_rating_history (outfit_id, stylist_id, version, vote, modified_at)
        SELECT outfit_id, stylist_id, version, vote, modified_at
        FROM outfit_rating WHERE outfit_id = ?
    """, outfitId)

    fun timeline(outfitId: Long, pageable: Pageable): Page<RatingHistoryRow> {
        val rows = jdbc.query("""
            SELECT * FROM (
                SELECT stylist_id, vote, version, modified_at, archived_at
                FROM outfit_rating_history WHERE outfit_id = ?
                UNION ALL
                SELECT stylist_id, vote, version, modified_at, NULL::timestamptz AS archived_at
                FROM outfit_rating WHERE outfit_id = ?
            ) ratings ORDER BY modified_at DESC, stylist_id DESC, version DESC LIMIT ? OFFSET ?
        """, { row, _ -> RatingHistoryRow(row.getLong("stylist_id"), row.getString("vote"), row.getLong("version"),
            row.getTimestamp("modified_at").toInstant(), row.getTimestamp("archived_at")?.toInstant()) },
            outfitId, outfitId, pageable.pageSize, pageable.offset)
        val count = jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM outfit_rating_history WHERE outfit_id = ?)
                 + (SELECT count(*) FROM outfit_rating WHERE outfit_id = ?)
        """, Long::class.java, outfitId, outfitId)!!
        return PageImpl(rows, pageable, count)
    }
}
