package ru.itmo.clothesadvisor.service.weather

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.model.weather.PrecipitationType
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository

@Service
@Transactional(readOnly = true)
internal class PrecipitationTypeService(
    private val precipitationTypes: PrecipitationTypeRepository,
) {
    fun list(pageable: Pageable): List<PrecipitationType> =
        precipitationTypes.findAllByOrderByIdAsc(pageable)
}
