package ru.itmo.clothesadvisor.repository.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory

internal interface WardrobeCategoryRepository : Repository<WardrobeCategory, Long> {
    fun findById(id: Long): WardrobeCategory?

    fun findAllByOrderByIdAsc(pageable: Pageable): List<WardrobeCategory> =
        findPage(pageable.pageSize, pageable.offset)

    @Query("SELECT * FROM wardrobe_category ORDER BY id LIMIT :limit OFFSET :offset")
    fun findPage(limit: Int, offset: Long): List<WardrobeCategory>
}
