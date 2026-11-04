package ru.itmo.clothesadvisor.accountservice.controller.user

import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.controller.pageRequest
import ru.itmo.clothesadvisor.accountservice.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.accountservice.dto.user.UpdateUserRoleAndStatusRequest
import ru.itmo.clothesadvisor.accountservice.service.user.AdminUserService

@Tag(
    name = "Пользователи",
    description = "Требуется активный администратор.",
)
@RestController
@RequestMapping("/api/admin/users")
internal class AdminUserController(private val users: AdminUserService) {
    @GetMapping
    @Operation(summary = "Список аккаунтов", description = "Включая заблокированные, новые первыми.")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Успешно",
            headers = [Header(
                name = "X-Total-Count",
                description = "Общее число записей",
                schema = Schema(type = "integer", format = "int64"),
            )],
            useReturnTypeSchema = true,
        ),
        ApiResponse(
            responseCode = "400", description = "Некорректные параметры запроса",
            content = [Content()],
        ),
    )
    fun list(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = users.list(pageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Карточка аккаунта", description = "Без пароля и хеша.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "404", description = "Учётная запись не найдена; пустое тело",
            content = [Content()],
        ),
    )
    fun get(@PathVariable id: Long) = users.get(id)

    @PutMapping("/{id}")
    @Operation(
        summary = "Изменить роль и статус",
        description = "Требуется актуальная version. Нельзя заблокировать себя или снять свою роль; " +
            "последний активный ADMIN сохраняется.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "400", description = "Некорректные данные",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403", description = "Требуется активный администратор",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404", description = "Учётная запись не найдена; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "409", description = "Конфликт версии или состояния; пустое тело",
            content = [Content()],
        ),
    )
    fun update(
        @AuthenticationPrincipal actor: Jwt,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateUserRoleAndStatusRequest,
    ) = users.update(
        actor.subject!!.toLong(),
        id,
        request,
    )
}
