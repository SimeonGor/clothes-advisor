package ru.itmo.clothesadvisor.repository.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory

@Repository
internal class WardrobeCategoryRepository(private val jdbc: JdbcTemplate) {
    fun findById(id: Long): WardrobeCategory? = jdbc.query("SELECT * FROM wardrobe_category WHERE id = ?",
        { row, _ -> WardrobeCategory(row.getString("code"), row.getString("name")).apply { this.id = row.getLong("id") } }, id).singleOrNull()

    fun findAllByOrderByIdAsc(pageable: Pageable): List<WardrobeCategory> =
        jdbc.query("SELECT * FROM wardrobe_category ORDER BY id LIMIT ? OFFSET ?",
            { row, _ -> WardrobeCategory(row.getString("code"), row.getString("name")).apply { id = row.getLong("id") } },
            pageable.pageSize, pageable.offset)
}
