package ru.itmo.clothesadvisor.controller.wardrobe

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
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeCategoryResponse

@Tag(name = "Справочники", description = "Чтение для действующего пользователя с JWT.")
@RequestMapping("/api/wardrobe/categories")
internal interface WardrobeCategoryApi {
    @GetMapping
    @Operation(summary = "Категории вещей", description = "Массив справочника; без X-Total-Count.")
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
    ): Mono<List<WardrobeCategoryResponse>>
}
