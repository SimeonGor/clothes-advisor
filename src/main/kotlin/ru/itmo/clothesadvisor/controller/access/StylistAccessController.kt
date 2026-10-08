package ru.itmo.clothesadvisor.controller.access

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.service.access.AccessGrantService

@Tag(name = "Доступ стилистов", description = "Только USER: управление доступом к своему гардеробу и образам.")
@RestController
@RequestMapping("/api/me/stylist-access")
@PreAuthorize("hasRole('USER')")
internal class StylistAccessController(private val access: AccessGrantService) {
    @PutMapping("/{stylistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Предоставить доступ стилисту", description = "Идемпотентно. Получатель должен быть активным STYLIST.")
    @ApiResponses(
        ApiResponse(responseCode = "404", description = "Получатель не найден или не является активным STYLIST; пустое тело", content = [Content()]),
    )
    fun grant(@AuthenticationPrincipal user: CurrentUser, @PathVariable stylistId: Long) =
        access.grant(user.id, stylistId)

    @DeleteMapping("/{stylistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отозвать доступ", description = "Идемпотентно: отсутствие разрешения не является ошибкой.")
    fun revoke(@AuthenticationPrincipal user: CurrentUser, @PathVariable stylistId: Long) =
        access.revoke(user.id, stylistId)

    @GetMapping
    @Operation(summary = "Список получателей доступа", description = "Массив получателей; без X-Total-Count.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
    )
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = access.listRecipients(user.id, toPageRequest(page, size))
}
