package ru.itmo.clothesadvisor.controller.stylistrequest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.stylistrequest.CancelStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CreateStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.StylistRequestResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser

@Tag(name = "Запросы владельца стилисту")
@RequestMapping("/api/me/stylist-requests")
@ApiResponses(
    ApiResponse(responseCode = "404", description = "Запрос или участник операции не найдены", content = [Content()]),
    ApiResponse(responseCode = "503", description = "Account недоступен", content = [Content()]),
)
internal interface StylistRequestApi {
    @PostMapping
    @Operation(summary = "Создать запрос", description = "USER; выбранному стилисту нужен действующий доступ. Пожелания до 2000 символов.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", useReturnTypeSchema = true,
            headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))]),
        ApiResponse(responseCode = "409", description = "Конфликт данных — пустое тело; Idempotency-Key — код ошибки",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    )
    fun create(
        @Parameter(hidden = true) user: CurrentUser,
        @Valid @RequestBody request: CreateStylistRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): Mono<ResponseEntity<StylistRequestResponse>>

    @GetMapping
    @Operation(summary = "Свои запросы", description = "По ID по убыванию; общее число в X-Total-Count.")
    @ApiResponse(responseCode = "200", description = "Список", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", schema = Schema(type = "integer", format = "int64"))])
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): Mono<ResponseEntity<List<StylistRequestResponse>>>

    @GetMapping("/{id}")
    @Operation(summary = "Прочитать свой запрос")
    @ApiResponse(responseCode = "200", description = "Запрос", useReturnTypeSchema = true)
    fun get(@Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long): Mono<StylistRequestResponse>

    @PutMapping("/{id}/cancellation")
    @Operation(summary = "Отменить запрос", description = "Нужна текущая version; повтор отмены её не меняет. Устаревшая версия: 409.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Отменённый запрос", useReturnTypeSchema = true),
        ApiResponse(responseCode = "409", description = "Конфликт версии или состояния", content = [Content()]),
    )
    fun cancel(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: CancelStylistRequest,
    ): Mono<StylistRequestResponse>
}
