package ru.itmo.clothesadvisor.controller.wardrobe

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.Part
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.service.wardrobe.ReactiveWardrobeService

@Tag(
    name = "Фотографии владельца",
    description = "Действующий пользователь; владелец определяется по проверенному JWT.",
)
@RestController
@RequestMapping("/api/wardrobe/items/{itemId}/photos")
internal class WardrobeItemPhotoController(private val wardrobe: ReactiveWardrobeService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Добавить фотографии",
        description =
            "Обязательные повторяющиеся части photos, JPEG/PNG, каждый файл до 10 000 000 байт, всего не более 5 фото вещи. Возвращает массив без Location.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description =
                "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Конфликт данных: пустое тело. Конфликт Idempotency-Key: ApiErrorResponse с idempotency_key_reused, idempotency_in_progress или idempotency_failed.",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ru.itmo.clothesadvisor.dto.ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "413",
            description = "Превышен размер файла или multipart-запроса; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "415",
            description = "Неподдерживаемый формат изображения; пустое тело",
            content = [Content()],
        ),
        ApiResponse(
            responseCode = "503",
            description = "Хранилище недоступно или подключение не настроено; пустое тело",
            content = [Content()],
        ),
    )
    fun add(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable itemId: Long,
        @Parameter(array = ArraySchema(schema = Schema(type = "string", format = "binary")))
        @RequestPart("photos", required = false) files: List<@JvmSuppressWildcards Part>,
        @Parameter(description = "Ключ повтора: 1..255 печатных ASCII-символов. Другие данные, незавершённый или неуспешный запрос: 409; успех повторяется без выполнения.")
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): Mono<List<WardrobeItemPhotoResponse>> =
        readPhotoParts(files).flatMap { bytes ->
            wardrobe.addPhotos(user.id, itemId, bytes, key)
        }

    @GetMapping
    @Operation(summary = "Список фотографий", description = "Метаданные без S3-ссылок.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
    )
    fun list(@Parameter(hidden = true) user: CurrentUser, @PathVariable itemId: Long) =
        wardrobe.listPhotos(user.id, itemId)

    @GetMapping("/{photoId}/content")
    @Operation(
        summary = "Скачать фотографию",
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
    fun content(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable itemId: Long,
        @PathVariable photoId: Long,
    ) =
        wardrobe.getPhotoContent(user.id, itemId, photoId)
            .map { it.toPhotoContentResponse() }

    @DeleteMapping("/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Удалить фотографию",
        description = "Удаляется запись БД; объект S3 сохраняется.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "404",
            description =
                "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело",
            content = [Content()],
        ),
    )
    fun delete(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable itemId: Long,
        @PathVariable photoId: Long,
    ) =
        wardrobe.deletePhoto(user.id, itemId, photoId)
}
