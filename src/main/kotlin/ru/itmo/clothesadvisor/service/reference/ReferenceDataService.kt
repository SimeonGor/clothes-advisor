package ru.itmo.clothesadvisor.service.reference

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.model.reference.GarmentCategory
import ru.itmo.clothesadvisor.model.reference.PrecipitationType
import ru.itmo.clothesadvisor.repository.reference.GarmentCategoryRepository
import ru.itmo.clothesadvisor.repository.reference.PrecipitationTypeRepository

@Service
@Transactional(readOnly = true)
internal class ReferenceDataService(
    private val garmentCategories: GarmentCategoryRepository,
    private val precipitationTypes: PrecipitationTypeRepository,
) {
    fun garmentCategories(pageable: Pageable): List<GarmentCategory> =
        garmentCategories.findAllByOrderByIdAsc(pageable)

    fun precipitationTypes(pageable: Pageable): List<PrecipitationType> =
        precipitationTypes.findAllByOrderByIdAsc(pageable)
}
