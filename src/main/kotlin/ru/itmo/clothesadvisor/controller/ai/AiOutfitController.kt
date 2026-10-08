package ru.itmo.clothesadvisor.controller.ai

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag

import jakarta.validation.Valid
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.service.ai.AiOutfitService

@Tag(name = "Подбор AI", description = "Только USER: подбор из своего гардероба.")
@RestController
@PreAuthorize("hasRole('USER')")
internal class AiOutfitController(private val outfits: AiOutfitService) {
    @PostMapping("/api/outfits/ai")
    @Operation(summary = "Подобрать образ через AI", description = "Без candidateItemIds или с null используется весь гардероб. Вещи без фото пропускаются. После фильтрации требуется 1..20 кандидатов; используется одно фото каждой вещи. Внешний вызов до 60 секунд, без повторов. При ошибке образ не сохраняется.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", headers = [Header(name = "Location", description = "URI созданного ресурса", schema = Schema(type = "string", format = "uri"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ru.itmo.clothesadvisor.dto.ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Вещь или использованная фотография удалены во время подбора; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "422", description = "AI отказал или не нашёл подходящего образа; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "502", description = "Некорректный ответ AI; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "503", description = "Хранилище или AI недоступны, подключение не настроено либо исчерпан лимит AI; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "504", description = "Таймаут AI; пустое тело", content = [Content()]),
    )
    fun create(@AuthenticationPrincipal user: CurrentUser, @Valid @RequestBody request: CreateAiOutfitRequest) =
        outfits.create(user.id, request).let { ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it) }
}
