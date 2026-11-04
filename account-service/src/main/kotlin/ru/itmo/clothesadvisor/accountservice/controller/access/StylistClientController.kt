package ru.itmo.clothesadvisor.accountservice.controller.access

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.controller.pageRequest
import ru.itmo.clothesadvisor.accountservice.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.accountservice.service.access.AccessGrantService

@Tag(name = "Клиенты стилиста", description = "Требуется действующий пользователь.")
@RestController
@RequestMapping("/api/stylist/clients")
internal class StylistClientController(private val access: AccessGrantService) {
    @GetMapping
    @Operation(summary = "Список клиентов", description = "Без X-Total-Count.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "400", description = "Некорректные параметры",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403", description = "Действующий пользователь заблокирован",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404", description = "Учётная запись не найдена; пустое тело",
            content = [Content()],
        ),
    )
    fun list(
        @AuthenticationPrincipal actor: Jwt,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = access.listClients(actor.subject!!.toLong(), pageRequest(page, size))
}
