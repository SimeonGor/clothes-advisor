package ru.itmo.clothesadvisor.controller.access

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.service.access.AccessGrantService

@Tag(name = "Клиенты стилиста", description = "Только STYLIST: клиенты с действующим разрешением.")
@RestController
@RequestMapping("/api/stylist/clients")
@PreAuthorize("hasRole('STYLIST')")
internal class StylistClientController(private val access: AccessGrantService) {
    @GetMapping
    @Operation(summary = "Список клиентов", description = "Массив клиентов; без X-Total-Count.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
    )
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = access.listClients(user.id, toPageRequest(page, size))
}
