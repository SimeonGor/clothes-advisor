package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeCategoryResponse
import ru.itmo.clothesadvisor.service.wardrobe.ReactiveWardrobeCategoryService

@RestController
internal class WardrobeCategoryController(
    private val wardrobeCategories: ReactiveWardrobeCategoryService,
) : WardrobeCategoryApi {
    override fun list(
        page: Int,
        size: Int,
    ): Mono<List<WardrobeCategoryResponse>> =
        wardrobeCategories.list(toPageRequest(page, size)).map { categories ->
            categories.map {
                WardrobeCategoryResponse(requireNotNull(it.id), it.code, it.name)
            }
        }
}
