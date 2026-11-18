package ru.itmo.clothesadvisor.accountservice.controller.access

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.accountservice.dto.access.AccessRecipientResponse

@Tag(name = "Доступ стилистов", description = "Требуется активный пользователь с ролью USER.")
@RequestMapping("/api/me/stylist-access")
@ApiResponses(
    ApiResponse(responseCode = "400", description = "Некорректные параметры", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))]),
    ApiResponse(responseCode = "403", description = "Действующий пользователь заблокирован", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))]),
    ApiResponse(responseCode = "404", description = "Учётная запись не найдена; пустое тело", content = [Content()]),
)
internal interface StylistAccessApi {
    @PutMapping("/{stylistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Предоставить доступ стилисту", description = "Идемпотентно. Получатель должен быть активным STYLIST.")
    @ApiResponse(responseCode = "204", description = "Доступ предоставлен", content = [Content()])
    fun grant(@AuthenticationPrincipal actor: Jwt, @PathVariable stylistId: Long): Mono<Void>

    @DeleteMapping("/{stylistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отозвать доступ", description = "Идемпотентно: отсутствие разрешения не является ошибкой.")
    @ApiResponse(responseCode = "204", description = "Доступ отозван", content = [Content()])
    fun revoke(@AuthenticationPrincipal actor: Jwt, @PathVariable stylistId: Long): Mono<Void>

    @GetMapping
    @Operation(summary = "Список получателей доступа", description = "Без X-Total-Count.")
    @ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true)
    fun list(@AuthenticationPrincipal actor: Jwt, @RequestParam(defaultValue = "0") @Min(0) page: Int, @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int): Mono<List<AccessRecipientResponse>>
}
