package ru.itmo.clothesadvisor.repository.weather

import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.weather.PrecipitationType

internal interface PrecipitationTypeRepository : Repository<PrecipitationType, Long> {
    fun findAllByOrderByIdAsc(pageable: Pageable): List<PrecipitationType>
}
