package ru.itmo.clothesadvisor.controller.calendar

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
import jakarta.validation.constraints.Positive
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
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
import ru.itmo.clothesadvisor.dto.calendar.CalendarEntryResponse
import ru.itmo.clothesadvisor.dto.calendar.CreateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.calendar.UpdateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser

@Tag(name = "Календарь образов")
@RequestMapping("/api/me/calendar")
@ApiResponses(
    ApiResponse(responseCode = "404", description = "Своя запись или образ не найдены", content = [Content()]),
    ApiResponse(responseCode = "503", description = "Account недоступен", content = [Content()]),
)
internal interface CalendarEntryApi {
    @PostMapping
    @Operation(summary = "Добавить образ в календарь", description = "Только USER; один образ не повторяется на одну дату. Idempotency-Key необязателен.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", useReturnTypeSchema = true,
            headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))]),
        ApiResponse(responseCode = "409", description = "Дубликат или удалённый образ — пустое тело; Idempotency-Key — код ошибки",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    )
    fun create(
        @Parameter(hidden = true) user: CurrentUser,
        @Valid @RequestBody request: CreateCalendarEntryRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): Mono<ResponseEntity<CalendarEntryResponse>>

    @GetMapping
    @Operation(summary = "Свои записи за период", description = "Только USER; from ≤ to, обе даты включительно. По дате и ID по возрастанию.")
    @ApiResponse(responseCode = "200", description = "Список", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", schema = Schema(type = "integer", format = "int64"))])
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): Mono<ResponseEntity<List<CalendarEntryResponse>>>

    @GetMapping("/{id}")
    @Operation(summary = "Прочитать свою запись", description = "Только USER.")
    @ApiResponse(responseCode = "200", description = "Запись", useReturnTypeSchema = true)
    fun get(@Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long): Mono<CalendarEntryResponse>

    @PutMapping("/{id}")
    @Operation(summary = "Изменить свою запись", description = "Только USER; текущая version обязательна. Повтор без изменений сохраняет версию и даты.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Запись", useReturnTypeSchema = true),
        ApiResponse(responseCode = "409", description = "Устаревшая версия, дубликат или удалённый образ", content = [Content()]),
    )
    fun update(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateCalendarEntryRequest,
    ): Mono<CalendarEntryResponse>

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить свою запись", description = "Только USER; текущая version обязательна. Образ и история сохраняются.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Удалено", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Устаревшая версия", content = [Content()]),
    )
    fun delete(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable id: Long,
        @RequestParam @Positive version: Long,
    ): Mono<ResponseEntity<Void>>
}
