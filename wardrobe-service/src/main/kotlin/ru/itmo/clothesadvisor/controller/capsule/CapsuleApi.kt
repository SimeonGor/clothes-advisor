package ru.itmo.clothesadvisor.controller.capsule

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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.capsule.CapsuleResponse
import ru.itmo.clothesadvisor.dto.capsule.CreateCapsuleRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser

@Tag(name = "Капсулы")
@RequestMapping("/api/me/capsules")
@ApiResponses(
    ApiResponse(responseCode = "404", description = "Своя капсула или вещь не найдены", content = [Content()]),
    ApiResponse(responseCode = "503", description = "Account недоступен", content = [Content()]),
)
internal interface CapsuleApi {
    @PostMapping
    @Operation(summary = "Создать капсулу", description = "Только USER; неизменяемый набор 1–20 своих вещей. Доступны вещи в любом состоянии. Idempotency-Key необязателен.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", useReturnTypeSchema = true,
            headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))]),
        ApiResponse(responseCode = "409", description = "Удалённая вещь — пустое тело; конфликт Idempotency-Key — код ошибки",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    )
    fun create(
        @Parameter(hidden = true) user: CurrentUser,
        @Valid @RequestBody request: CreateCapsuleRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): Mono<ResponseEntity<CapsuleResponse>>

    @GetMapping
    @Operation(summary = "Список своих капсул", description = "Только USER; по ID по убыванию.")
    @ApiResponse(responseCode = "200", description = "Список", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", schema = Schema(type = "integer", format = "int64"))])
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): Mono<ResponseEntity<List<CapsuleResponse>>>

    @GetMapping("/{id}")
    @Operation(summary = "Прочитать свою капсулу", description = "Только USER.")
    @ApiResponse(responseCode = "200", description = "Капсула", useReturnTypeSchema = true)
    fun get(@Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long): Mono<CapsuleResponse>

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить свою капсулу", description = "Только USER; вещи сохраняются.")
    @ApiResponse(responseCode = "204", description = "Удалено", content = [Content()])
    fun delete(@Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long): Mono<ResponseEntity<Void>>
}
