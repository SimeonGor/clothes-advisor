package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeCategoryRepository

@Service
@Transactional(readOnly = true)
internal class WardrobeCategoryService(
    private val wardrobeCategories: WardrobeCategoryRepository,
) {
    fun list(pageable: Pageable): List<WardrobeCategory> =
        wardrobeCategories.findAllByOrderByIdAsc(pageable)
}
