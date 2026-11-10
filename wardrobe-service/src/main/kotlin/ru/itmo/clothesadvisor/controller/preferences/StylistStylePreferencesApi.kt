package ru.itmo.clothesadvisor.controller.preferences

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.preferences.StylePreferencesResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser

@Tag(name = "Предпочтения клиента", description = "Только чтение стилистом с действующим доступом.")
@RequestMapping("/api/stylist/clients/{ownerId}/style-preferences")
internal interface StylistStylePreferencesApi {
    @GetMapping
    @Operation(summary = "Получить предпочтения клиента")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Профиль клиента", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Нет профиля или доступа", content = [Content()]),
        ApiResponse(responseCode = "503", description = "Account недоступен", content = [Content()]),
    )
    fun get(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
    ): Mono<StylePreferencesResponse>
}
