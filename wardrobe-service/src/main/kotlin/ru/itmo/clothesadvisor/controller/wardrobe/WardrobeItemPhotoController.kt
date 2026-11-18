package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.http.codec.multipart.Part
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.service.wardrobe.ReactiveWardrobeService

@RestController
internal class WardrobeItemPhotoController(private val wardrobe: ReactiveWardrobeService) : WardrobeItemPhotoApi {
    override fun add(
        user: CurrentUser,
        itemId: Long,
        files: List<@JvmSuppressWildcards Part>,
        key: String?,
    ): Mono<List<WardrobeItemPhotoResponse>> =
        readPhotoParts(files).flatMap { bytes ->
            wardrobe.addPhotos(user.id, itemId, bytes, key)
        }

    override fun list(user: CurrentUser, itemId: Long) =
        wardrobe.listPhotos(user.id, itemId)

    override fun content(
        user: CurrentUser,
        itemId: Long,
        photoId: Long,
    ) =
        wardrobe.getPhotoContent(user.id, itemId, photoId)
            .map { it.toPhotoContentResponse() }

    override fun delete(
        user: CurrentUser,
        itemId: Long,
        photoId: Long,
    ) =
        wardrobe.deletePhoto(user.id, itemId, photoId)
}
