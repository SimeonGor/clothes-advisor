package ru.itmo.clothesadvisor.repository.weather

import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.weather.PrecipitationType

internal interface PrecipitationTypeRepository : Repository<PrecipitationType, Long> {
    fun findById(id: Long): PrecipitationType?
    fun existsById(id: Long): Boolean
    fun findAllByOrderByIdAsc(pageable: Pageable): List<PrecipitationType>
}
