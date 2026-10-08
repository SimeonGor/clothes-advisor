package ru.itmo.clothesadvisor.controller.wardrobe

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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeManagementService

@Tag(name = "Гардероб владельца", description = "Только USER: собственные вещи.")
@RestController
@RequestMapping("/api/wardrobe/items")
@PreAuthorize("hasRole('USER')")
internal class WardrobeItemController(
    private val items: WardrobeItemService,
    private val management: WardrobeManagementService,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Создать вещь", description = "JSON или multipart: обязательный JSON item, необязательные повторяющиеся photos. До 5 JPEG/PNG по 10 000 000 байт. При ошибке вещь не создаётся.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", headers = [Header(name = "Location", description = "URI созданного ресурса", schema = Schema(type = "string", format = "uri"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Конфликт ограничений данных; пустое тело", content = [Content()]),
    )
    fun create(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: CreateWardrobeItemRequest,
    ): ResponseEntity<WardrobeItemResponse> {
        val created = items.create(user.id, request)
        return ResponseEntity.created(URI("/api/wardrobe/items/${created.id}")).body(created)
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = [Content(mediaType = "multipart/form-data", encoding = [
            io.swagger.v3.oas.annotations.media.Encoding(name = "item", contentType = "application/json"),
        ])],
    )
    @Operation(summary = "Создать вещь", description = "JSON или multipart: обязательный JSON item, необязательные повторяющиеся photos. До 5 JPEG/PNG по 10 000 000 байт. При ошибке вещь не создаётся.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", headers = [Header(name = "Location", description = "URI созданного ресурса", schema = Schema(type = "string", format = "uri"))], useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Конфликт ограничений данных; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "413", description = "Превышен размер файла или multipart-запроса; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "415", description = "Неподдерживаемый формат изображения; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "503", description = "Хранилище недоступно или подключение не настроено; пустое тело", content = [Content()]),
    )
    fun createWithPhotos(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestPart("item") request: CreateWardrobeItemRequest,
        @RequestPart("photos", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<WardrobeItemResponse> {
        val created = management.createWithPhotos(user.id, request, files.orEmpty())
        return ResponseEntity.created(URI("/api/wardrobe/items/${created.id}")).body(created)
    }

    @GetMapping
    @Operation(summary = "Список вещей", description = "По ID по возрастанию; без X-Total-Count.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
    )
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): List<WardrobeItemResponse> = items.list(user.id, toPageRequest(page, size))

    @GetMapping("/{id}")
    @Operation(summary = "Карточка вещи", description = "Только собственная вещь.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun get(@AuthenticationPrincipal user: CurrentUser, @PathVariable id: Long): WardrobeItemResponse =
        items.get(user.id, id)

    @PutMapping("/{id}")
    @Operation(summary = "Изменить вещь", description = "Требуется актуальная положительная version и существующая categoryId.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Конфликт версии, состояния или ограничений данных; пустое тело", content = [Content()]),
    )
    fun update(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateWardrobeItemRequest,
    ): WardrobeItemResponse = items.update(user.id, id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить вещь", description = "Требуется актуальная положительная version; конфликты зависимостей — 409.")
    @ApiResponses(
        ApiResponse(responseCode = "400", description = "Некорректные параметры запроса", content = [Content()]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Конфликт версии, состояния или ограничений данных; пустое тело", content = [Content()]),
    )
    fun delete(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable id: Long,
        @RequestParam @Positive version: Long,
    ) = items.delete(user.id, id, version)
}
