package ru.itmo.clothesadvisor.repository.weather

import org.springframework.data.domain.Pageable
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.weather.PrecipitationType

internal interface PrecipitationTypeRepository : Repository<PrecipitationType, Long> {
    fun findById(id: Long): PrecipitationType?
    fun existsById(id: Long): Boolean

    fun findAllByOrderByIdAsc(pageable: Pageable): List<PrecipitationType> =
        findPage(pageable.pageSize, pageable.offset)

    @Query("SELECT * FROM precipitation_type ORDER BY id LIMIT :limit OFFSET :offset")
    fun findPage(limit: Int, offset: Long): List<PrecipitationType>
}
