package ru.itmo.clothesadvisor.controller.preferences

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.preferences.PutStylePreferencesRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.preferences.ReactiveStylePreferencesService

@Tag(name = "Предпочтения владельца", description = "Только действующий USER.")
@RestController
@RequestMapping("/api/me/style-preferences")
@ApiResponses(
    ApiResponse(responseCode = "503", description = "Account недоступен", content = [Content()]),
)
internal class StylePreferencesController(private val preferences: ReactiveStylePreferencesService) {
    @GetMapping
    @Operation(summary = "Получить предпочтения", description = "404, если профиль ещё не создан.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Профиль", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Профиль отсутствует", content = [Content()]),
    )
    fun get(@Parameter(hidden = true) user: CurrentUser) = preferences.get(user)

    @PutMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Сохранить предпочтения",
        description = "Заменяет все три поля (до 2000 символов каждое); null и пропуск означают пустую строку. " +
            "Без version создаёт профиль, для обновления нужна текущая положительная version. " +
            "Неизменные значения сохраняют версию и даты; устаревшая версия даёт 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сохранённый профиль", useReturnTypeSchema = true),
        ApiResponse(responseCode = "409", description = "Конфликт версии или состояния", content = [Content()]),
    )
    fun put(
        @Parameter(hidden = true) user: CurrentUser,
        @Valid @RequestBody request: PutStylePreferencesRequest,
    ) = preferences.put(user, request)
}
