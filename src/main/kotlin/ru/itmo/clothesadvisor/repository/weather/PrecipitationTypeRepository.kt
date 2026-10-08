package ru.itmo.clothesadvisor.repository.weather

import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.weather.PrecipitationType

@Repository
internal class PrecipitationTypeRepository(private val jdbc: JdbcTemplate) {
    fun findById(id: Long): PrecipitationType? = jdbc.query("SELECT * FROM precipitation_type WHERE id = ?",
        { row, _ -> PrecipitationType(row.getString("code"), row.getString("name")).apply { this.id = row.getLong("id") } }, id).singleOrNull()

    fun existsById(id: Long): Boolean =
        jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM precipitation_type WHERE id = ?)", Boolean::class.java, id)!!

    fun findAllByOrderByIdAsc(pageable: Pageable): List<PrecipitationType> =
        jdbc.query("SELECT * FROM precipitation_type ORDER BY id LIMIT ? OFFSET ?",
            { row, _ -> PrecipitationType(row.getString("code"), row.getString("name")).apply { id = row.getLong("id") } },
            pageable.pageSize, pageable.offset)
}
