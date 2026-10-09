package ru.itmo.clothesadvisor.controller.wardrobe

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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.wardrobe.StylistWardrobeService

@Tag(
    name = "Гардероб клиента",
    description =
        "Действующий пользователь; доступ стилиста проверяется бизнес-правилами, отсутствие доступа — 404.",
)
@RestController
@RequestMapping("/api/stylist/clients/{ownerId}/wardrobe/items")
internal class StylistWardrobeController(private val wardrobe: StylistWardrobeService) {
    @GetMapping
    @Operation(
        summary = "Список вещей клиента",
        description = "По ID по возрастанию; без X-Total-Count.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
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
    fun listItems(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = wardrobe.listItems(user.id, ownerId, toPageRequest(page, size))

    @GetMapping("/{itemId}")
    @Operation(summary = "Карточка вещи клиента", description = "Только просмотр.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
    )
    fun getItem(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable itemId: Long,
    ) = wardrobe.getItem(user.id, ownerId, itemId)

    @GetMapping("/{itemId}/photos")
    @Operation(summary = "Фотографии вещи клиента", description = "Метаданные без S3-ссылок.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
    )
    fun listPhotos(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable itemId: Long,
    ) = wardrobe.listPhotos(user.id, ownerId, itemId)

    @GetMapping("/{itemId}/photos/{photoId}/content")
    @Operation(
        summary = "Скачать фотографию клиента",
        description = "Исходные байты JPEG/PNG; без Content-Disposition.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Байты изображения",
            content =
                [
                    Content(
                        mediaType = "image/jpeg",
                        schema = Schema(type = "string", format = "binary"),
                    ),
                    Content(
                        mediaType = "image/png",
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            headers =
                [
                    Header(
                        name = "Cache-Control",
                        schema = Schema(type = "string", allowableValues = ["no-store"]),
                    ),
                    Header(
                        name = "X-Content-Type-Options",
                        schema = Schema(type = "string", allowableValues = ["nosniff"]),
                    ),
                ],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "503",
            description = "Хранилище недоступно или подключение не настроено; пустое тело",
            content = [Content()],
        ),
    )
    fun getPhotoContent(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable itemId: Long,
        @PathVariable photoId: Long,
    ) = wardrobe.getPhotoContent(user.id, ownerId, itemId, photoId).toPhotoContentResponse()
}
