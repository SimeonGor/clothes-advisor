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
import jakarta.validation.constraints.Positive
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.rating.CreateRatingRequest
import ru.itmo.clothesadvisor.dto.rating.UpdateRatingRequest
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService

@Tag(name = "Образы и оценки стилиста", description = "Только STYLIST с действующим разрешением владельца; отсутствие доступа скрывается как 404.")
@RestController
@RequestMapping("/api/stylist/clients/{ownerId}/outfits")
@PreAuthorize("hasRole('STYLIST')")
internal class StylistOutfitController(private val outfits: OutfitService, private val ratings: OutfitRatingService) {
    @PostMapping
    @Operation(summary = "Создать образ клиента", description = "1..50 различных положительных itemIds клиента. Ответ плоский, с myRating.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", headers = [Header(name = "Location", description = "URI созданного ресурса", schema = Schema(type = "string", format = "uri"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Конфликт ограничений данных; пустое тело", content = [Content()]),
    )
    fun create(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @Valid @RequestBody request: CreateOutfitRequest,
    ) = outfits.createForClient(user.id, ownerId, request).let {
        ResponseEntity.created(URI("/api/stylist/clients/$ownerId/outfits/${it.outfit.id}")).body(it)
    }

    @GetMapping
    @Operation(summary = "Список образов клиента", description = "По ID по убыванию, с собственной оценкой myRating.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", headers = [Header(name = "X-Total-Count", description = "Общее число записей", schema = Schema(type = "integer", format = "int64"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = outfits.listForClient(user.id, ownerId, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Карточка образа клиента", description = "Плоский ответ с nullable myRating.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun get(@AuthenticationPrincipal user: CurrentUser, @PathVariable ownerId: Long, @PathVariable id: Long) =
        outfits.getForClient(user.id, ownerId, id)

    @PostMapping("/{id}/rating")
    @Operation(summary = "Оценить чужой образ", description = "Собственный образ оценивать нельзя. Повторная активная оценка — 409.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "У стилиста уже есть активная оценка этого образа или данные конфликтуют; пустое тело", content = [Content()]),
    )
    fun createRating(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateRatingRequest,
    ) = ResponseEntity.status(201).body(ratings.create(user.id, ownerId, id, request))

    @PutMapping("/{id}/rating")
    @Operation(summary = "Изменить свою оценку", description = "Требуется актуальная положительная version; старое состояние архивируется.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Версия оценки устарела или данные конфликтуют; пустое тело", content = [Content()]),
    )
    fun updateRating(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateRatingRequest,
    ) = ratings.update(user.id, ownerId, id, request)

    @DeleteMapping("/{id}/rating")
    @Operation(summary = "Отозвать свою оценку", description = "Требуется актуальная положительная version; отсутствие оценки — 404.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Выполнено; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Конфликт версии, состояния или ограничений данных; пустое тело", content = [Content()]),
    )
    fun withdrawRating(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable id: Long,
        @RequestParam @Positive version: Long,
    ): ResponseEntity<Void> {
        ratings.withdraw(user.id, ownerId, id, version)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/ratings/history")
    @Operation(summary = "История оценок клиента", description = "Текущие и архивные оценки доступного образа.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", headers = [Header(name = "X-Total-Count", description = "Общее число записей", schema = Schema(type = "integer", format = "int64"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun history(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = ratings.historyForClient(user.id, ownerId, id, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }
}
