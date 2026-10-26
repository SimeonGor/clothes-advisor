package ru.itmo.clothesadvisor.controller.ai

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.ai.ReactiveAiCapsuleService

@Tag(name = "Подбор AI")
@RestController
internal class AiCapsuleController(private val capsules: ReactiveAiCapsuleService) {
    @PostMapping("/api/me/capsules/ai")
    @Operation(summary = "Создать капсулу через AI", description = "Только подключённый USER. Без candidateItemIds или с null используются все доступные вещи с фото; требуется 1..20 кандидатов. Общий слот с подбором и заменой образов, без очереди. При ошибке капсула не сохраняется.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Капсула создана", useReturnTypeSchema = true,
            headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))]),
        ApiResponse(responseCode = "400", description = "Некорректный JSON/DTO: ApiErrorResponse; доменные ограничения: пустое тело",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Своя вещь не найдена; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Кандидаты или фото изменились: пустое тело. Idempotency-Key: idempotency_key_reused, idempotency_in_progress или idempotency_failed",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "422", description = "AI не нашёл капсулу; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "502", description = "Неверный ответ AI, включая отсутствующее объяснение; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "503", description = "Общий слот занят, account/S3/AI недоступны или нет подключения; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "504", description = "Таймаут AI; пустое тело", content = [Content()]),
    )
    fun create(
        @Parameter(hidden = true) user: CurrentUser,
        @Valid @RequestBody request: CreateAiCapsuleRequest,
        @Parameter(description = "Ключ повтора: 1..255 печатных ASCII-символов. Успешный ответ повторяется без AI, в том числе после удаления.")
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) = capsules.create(user.id, request, key).map {
        ResponseEntity.created(URI("/api/me/capsules/${it.id}")).body(it)
    }
}
