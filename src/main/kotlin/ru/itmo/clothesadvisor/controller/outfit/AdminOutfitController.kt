package ru.itmo.clothesadvisor.controller.outfit

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.outfit.AdminOutfitService

@Tag(
    name = "Модерация образов",
    description =
        "Действующий пользователь; административные ограничения проверяются бизнес-правилами.",
)
@RestController
@RequestMapping("/api/admin/users/{ownerId}/outfits")
internal class AdminOutfitController(private val outfits: AdminOutfitService) {
    @GetMapping
    @Operation(summary = "Список образов владельца", description = "По ID по убыванию.")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Успешно",
            headers =
                [
                    Header(
                        name = "X-Total-Count",
                        description = "Общее число записей",
                        schema = Schema(type = "integer", format = "int64"),
                    ),
                ],
            useReturnTypeSchema = true,
        ),
        ApiResponse(
            responseCode = "400",
            description = "Некорректные параметры запроса",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
    )
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) =
        outfits.list(user.id, ownerId, toPageRequest(page, size)).let {
            ResponseEntity.ok()
                .header("X-Total-Count", it.totalElements.toString())
                .body(it.content)
        }

    @GetMapping("/{id}")
    @Operation(
        summary = "Карточка образа владельца",
        description = "Проверяется соответствие владельца и образа.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
    )
    fun get(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable id: Long,
    ) = outfits.get(user.id, ownerId, id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Удалить образ владельца",
        description = "История оценок и вещи сохраняются.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Конфликт версии, состояния или ограничений данных; пустое тело",
            content = [Content()],
        ),
    )
    fun delete(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable id: Long,
    ) = outfits.delete(user.id, ownerId, id)
}
