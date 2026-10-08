package ru.itmo.clothesadvisor.repository.outfit

import java.sql.ResultSet
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.outfit.Outfit
import ru.itmo.clothesadvisor.model.outfit.OutfitSource

@Repository
internal class OutfitRepository(private val jdbc: JdbcTemplate) {
    private fun map(row: ResultSet) = Outfit(row.getLong("owner_id"), row.getLong("author_id"),
        OutfitSource.valueOf(row.getString("source")), row.getString("name"), row.getTimestamp("created_at").toInstant()).apply {
        id = row.getLong("id")
    }

    fun create(ownerId: Long, authorId: Long, source: OutfitSource, name: String): Outfit = jdbc.queryForObject("""
        INSERT INTO outfit (owner_id, author_id, source, name)
        VALUES (?, ?, ?::outfit_source, ?) RETURNING *
    """, { row, _ -> map(row) }, ownerId, authorId, source.name, name)

    fun findByIdAndOwnerId(id: Long, ownerId: Long): Outfit? =
        jdbc.query("SELECT * FROM outfit WHERE id = ? AND owner_id = ?", { row, _ -> map(row) }, id, ownerId).singleOrNull()

    fun findLockedByIdAndOwnerId(id: Long, ownerId: Long): Outfit? =
        jdbc.query("SELECT * FROM outfit WHERE id = ? AND owner_id = ? FOR UPDATE", { row, _ -> map(row) }, id, ownerId).singleOrNull()

    fun findAllByOwnerIdOrderByIdDesc(ownerId: Long, pageable: Pageable): Page<Outfit> {
        val rows = jdbc.query("SELECT * FROM outfit WHERE owner_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
            { row, _ -> map(row) }, ownerId, pageable.pageSize, pageable.offset)
        return PageImpl(rows, pageable, jdbc.queryForObject("SELECT count(*) FROM outfit WHERE owner_id = ?", Long::class.java, ownerId)!!)
    }

    fun delete(outfit: Outfit) {
        jdbc.update("DELETE FROM outfit WHERE id = ?", outfit.id)
    }
}
