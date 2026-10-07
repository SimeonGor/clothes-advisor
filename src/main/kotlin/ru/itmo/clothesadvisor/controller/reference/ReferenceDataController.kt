package ru.itmo.clothesadvisor.controller.reference

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.itmo.clothesadvisor.dto.reference.ReferenceItemResponse
import ru.itmo.clothesadvisor.service.reference.ReferenceDataService

@RestController
@RequestMapping("/api/reference")
internal class ReferenceDataController(private val referenceData: ReferenceDataService) {
    @GetMapping("/garment-categories")
    fun garmentCategories(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): List<ReferenceItemResponse> = referenceData.garmentCategories(pageRequest(page, size))
        .map { ReferenceItemResponse(requireNotNull(it.id), it.code, it.name) }

    @GetMapping("/precipitation-types")
    fun precipitationTypes(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): List<ReferenceItemResponse> = referenceData.precipitationTypes(pageRequest(page, size))
        .map { ReferenceItemResponse(requireNotNull(it.id), it.code, it.name) }

    private fun pageRequest(page: Int, size: Int): PageRequest {
        if (page.toLong() * size > Int.MAX_VALUE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Page offset exceeds the supported range")
        }
        return PageRequest.of(page, size)
    }
}
