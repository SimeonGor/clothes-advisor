package ru.itmo.clothesadvisor.repository.reference

import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.reference.GarmentCategory

internal interface GarmentCategoryRepository : Repository<GarmentCategory, Long> {
    fun findAllByOrderByIdAsc(pageable: Pageable): List<GarmentCategory>
}
