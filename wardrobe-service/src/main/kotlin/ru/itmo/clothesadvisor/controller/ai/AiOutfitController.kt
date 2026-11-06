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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.ai.ReactiveAiOutfitService

@Tag(
    name = "Подбор AI",
    description = "Действующий пользователь; владелец определяется по проверенному JWT.",
)
@RestController
internal class AiOutfitController(private val outfits: ReactiveAiOutfitService) {
    @PostMapping("/api/outfits/{id}/ai-replacements")
    @Operation(summary = "Заменить выбранные вещи через AI",
        description = "Создаёт новый образ, сохраняя остальные вещи, погоду, повод и стиль исходного. Исходный образ и оценки не меняются. Лимиты и ошибки подбора совпадают с POST /api/outfits/ai.")
    @ApiResponse(responseCode = "201", description = "Создан новый образ", useReturnTypeSchema = true,
        headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))])
    fun replace(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateAiOutfitReplacementRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) = outfits.replace(user.id, id, request, key).map {
        ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it)
    }

    @PostMapping("/api/outfits/ai")
    @Operation(
        summary = "Подобрать образ через AI",
        description =
            "Без candidateItemIds или с null используется весь гардероб. Вещи без фото пропускаются. После фильтрации требуется 1..20 кандидатов; используется одно фото каждой вещи. Внешний вызов до 60 секунд, без повторов. При ошибке образ не сохраняется.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "Создано",
            headers =
                [
                    Header(
                        name = "Location",
                        description = "URI созданного ресурса",
                        schema = Schema(type = "string", format = "uri"),
                    ),
                ],
            useReturnTypeSchema = true,
        ),
        ApiResponse(
            responseCode = "400",
            description =
                "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.",
            content =
                [
                    Content(
                        mediaType = "application/json",
                        schema =
                            Schema(
                                implementation = ru.itmo.clothesadvisor.dto.ApiErrorResponse::class,
                            ),
                    ),
                ],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Конфликт данных: пустое тело. Конфликт Idempotency-Key: ApiErrorResponse с idempotency_key_reused, idempotency_in_progress или idempotency_failed.",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ru.itmo.clothesadvisor.dto.ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "422",
            description = "AI отказал или не нашёл подходящего образа; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "502",
            description = "Некорректный ответ AI; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "503",
            description =
                "Хранилище или AI недоступны, подключение не настроено либо исчерпан лимит AI; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "504",
            description = "Таймаут AI; пустое тело",
            content = [Content()],
        ),
    )
    fun create(
        @Parameter(hidden = true) user: CurrentUser,
        @Valid @RequestBody request: CreateAiOutfitRequest,
        @Parameter(description = "Ключ повтора: 1..255 печатных ASCII-символов. Другие данные, незавершённый или неуспешный запрос: 409; успех повторяется без выполнения.")
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) =
        outfits.create(user.id, request, key).map {
            ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it)
        }
}
