package ru.itmo.clothesadvisor.controller.wardrobe

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

@Tag(name = "Фотографии владельца", description = "Действующий пользователь; владелец определяется по X-User-Id.")
@RestController
@RequestMapping("/api/wardrobe/items/{itemId}/photos")
internal class WardrobeItemPhotoController(private val photos: WardrobeItemPhotoService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить фотографии", description = "Обязательные повторяющиеся части photos, JPEG/PNG, каждый файл до 10 000 000 байт, всего не более 5 фото вещи. Возвращает массив без Location.")
    @ApiResponses(
        ApiResponse(responseCode = "400", description = "Некорректные данные, параметры или доменные ограничения. Ошибки JSON/DTO: ApiErrorResponse; доменные ошибки: пустое тело.", content = [Content()]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Превышен лимит 5 фотографий на вещь; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "413", description = "Превышен размер файла или multipart-запроса; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "415", description = "Неподдерживаемый формат изображения; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "503", description = "Хранилище недоступно или подключение не настроено; пустое тело", content = [Content()]),
    )
    fun add(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable itemId: Long,
        @RequestPart("photos") files: List<MultipartFile>,
    ): List<WardrobeItemPhotoResponse> = photos.add(user.id, itemId, files)

    @GetMapping
    @Operation(summary = "Список фотографий", description = "Метаданные без S3-ссылок.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Успешно", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun list(@Parameter(hidden = true) user: CurrentUser, @PathVariable itemId: Long) = photos.list(user.id, itemId)

    @GetMapping("/{photoId}/content")
    @Operation(summary = "Скачать фотографию", description = "Исходные байты JPEG/PNG; без Content-Disposition.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Байты изображения", content = [Content(mediaType = "image/jpeg", schema = Schema(type = "string", format = "binary")), Content(mediaType = "image/png", schema = Schema(type = "string", format = "binary"))], headers = [Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"])), Header(name = "X-Content-Type-Options", schema = Schema(type = "string", allowableValues = ["nosniff"]))]),
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
        ApiResponse(responseCode = "503", description = "Хранилище недоступно или подключение не настроено; пустое тело", content = [Content()]),
    )
    fun content(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable itemId: Long,
        @PathVariable photoId: Long,
    ) = photos.getContent(user.id, itemId, photoId).toPhotoContentResponse()

    @DeleteMapping("/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить фотографию", description = "Удаляется запись БД; объект S3 сохраняется.")
    @ApiResponses(
        ApiResponse(responseCode = "404", description = "Ресурс отсутствует, не принадлежит владельцу или доступ не предоставлен; пустое тело", content = [Content()]),
    )
    fun delete(@Parameter(hidden = true) user: CurrentUser, @PathVariable itemId: Long, @PathVariable photoId: Long) =
        photos.delete(user.id, itemId, photoId)
}
