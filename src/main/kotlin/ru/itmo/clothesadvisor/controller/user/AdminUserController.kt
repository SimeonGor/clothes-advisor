package ru.itmo.clothesadvisor.controller.user

import io.swagger.v3.oas.annotations.Parameter
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.user.UpdateUserRoleAndStatusRequest
import ru.itmo.clothesadvisor.service.user.AdminUserService

@Tag(name = "Пользователи", description = "Чтение публичное; изменение требует X-User-Id активного администратора.")
@RestController
@RequestMapping("/api/admin/users")
internal class AdminUserController(private val users: AdminUserService) {
    @GetMapping
    @Operation(summary = "Список аккаунтов", description = "Включая заблокированные, новые первыми.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", headers = [Header(name = "X-Total-Count", description = "Общее число записей", schema = Schema(type = "integer", format = "int64"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
    )
    fun list(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = users.list(toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Карточка аккаунта", description = "Без пароля и хеша.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Учётная запись не найдена; пустое тело", content = [Content()]),
    )
    fun get(@PathVariable id: Long) = users.get(id)

    @PutMapping("/{id}")
    @Operation(summary = "Изменить роль и статус", description = "Требуется актуальная version. Нельзя заблокировать себя или снять свою роль; последний активный ADMIN сохраняется.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ru.itmo.clothesadvisor.dto.ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Конфликт версии, состояния или ограничений данных; пустое тело", content = [Content()]),
    )
    fun update(
        @Parameter(hidden = true) actor: CurrentUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateUserRoleAndStatusRequest,
    ) = users.update(actor.id, id, request)
}
