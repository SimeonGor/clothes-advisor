package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.wardrobe.ReactiveStylistWardrobeService

@RestController
internal class StylistWardrobeController(private val wardrobe: ReactiveStylistWardrobeService) : StylistWardrobeApi {
    override fun listItems(
        user: CurrentUser,
        ownerId: Long,
        page: Int,
        size: Int,
    ) =
        wardrobe.listItems(user.id, ownerId, toPageRequest(page, size))

    override fun getItem(
        user: CurrentUser,
        ownerId: Long,
        itemId: Long,
    ) =
        wardrobe.getItem(user.id, ownerId, itemId)

    override fun listPhotos(
        user: CurrentUser,
        ownerId: Long,
        itemId: Long,
    ) =
        wardrobe.listPhotos(user.id, ownerId, itemId)

    override fun getPhotoContent(
        user: CurrentUser,
        ownerId: Long,
        itemId: Long,
        photoId: Long,
    ) =
        wardrobe.getPhotoContent(user.id, ownerId, itemId, photoId)
            .map { it.toPhotoContentResponse() }
}
