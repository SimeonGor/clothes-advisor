package ru.itmo.clothesadvisor.controller.weather

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.weather.PrecipitationTypeResponse
import ru.itmo.clothesadvisor.service.weather.PrecipitationTypeService

@Tag(name = "Справочники", description = "Публичное чтение; X-User-Id не требуется.")
@RestController
@RequestMapping("/api/weather/precipitation-types")
internal class PrecipitationTypeController(
    private val precipitationTypes: PrecipitationTypeService,
) {
    @GetMapping
    @Operation(summary = "Виды осадков", description = "Массив справочника; без X-Total-Count.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "400",
            description = "Некорректные параметры запроса",
            content = [Content()],
        ),
    )
    fun list(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): List<PrecipitationTypeResponse> =
        precipitationTypes.list(toPageRequest(page, size)).map {
            PrecipitationTypeResponse(requireNotNull(it.id), it.code, it.name)
        }
}
