package ru.itmo.clothesadvisor.controller.wardrobe

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeCategoryResponse
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeCategoryService

@RestController
@RequestMapping("/api/wardrobe/categories")
internal class WardrobeCategoryController(private val wardrobeCategories: WardrobeCategoryService) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): List<WardrobeCategoryResponse> = wardrobeCategories.list(toPageRequest(page, size))
        .map { WardrobeCategoryResponse(requireNotNull(it.id), it.code, it.name) }
}
