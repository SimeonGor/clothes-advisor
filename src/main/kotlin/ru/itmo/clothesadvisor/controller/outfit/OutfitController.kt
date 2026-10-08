package ru.itmo.clothesadvisor.controller.outfit

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
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService

@Tag(name = "Образы владельца", description = "Только USER: собственные образы.")
@RestController
@RequestMapping("/api/outfits")
@PreAuthorize("hasRole('USER')")
internal class OutfitController(private val outfits: OutfitService, private val ratings: OutfitRatingService) {
    @PostMapping
    @Operation(summary = "Создать ручной образ", description = "1..50 различных положительных itemIds своего гардероба; известный вид осадков.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", headers = [Header(name = "Location", description = "URI созданного ресурса", schema = Schema(type = "string", format = "uri"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Конфликт версии, состояния или ограничений данных; пустое тело", content = [Content()]),
    )
    fun create(@AuthenticationPrincipal user: CurrentUser, @Valid @RequestBody request: CreateOutfitRequest) =
        outfits.create(user.id, request).let { ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it) }

    @GetMapping
    @Operation(summary = "Список образов", description = "По ID по убыванию.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", headers = [Header(name = "X-Total-Count", description = "Общее число записей", schema = Schema(type = "integer", format = "int64"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
    )
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = outfits.list(user.id, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Карточка образа", description = "Только собственный образ.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun get(@AuthenticationPrincipal user: CurrentUser, @PathVariable id: Long) = outfits.get(user.id, id)

    @GetMapping("/{id}/ratings/history")
    @Operation(summary = "История оценок образа", description = "Включает текущие и архивные оценки.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", headers = [Header(name = "X-Total-Count", description = "Общее число записей", schema = Schema(type = "integer", format = "int64"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun history(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = ratings.history(user.id, id, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить образ", description = "История оценок и вещи сохраняются.")
    @ApiResponses(
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Конфликт версии, состояния или ограничений данных; пустое тело", content = [Content()]),
    )
    fun delete(@AuthenticationPrincipal user: CurrentUser, @PathVariable id: Long) = outfits.delete(user.id, id)
}
