package ru.itmo.clothesadvisor.controller.wardrobe

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.service.wardrobe.AdminWardrobeService

@RestController
@RequestMapping("/api/admin/users/{ownerId}/wardrobe/items")
@PreAuthorize("hasRole('ADMIN')")
internal class AdminWardrobeController(private val wardrobe: AdminWardrobeService) {
    @GetMapping
    fun listItems(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = wardrobe.listItems(user.id, ownerId, toPageRequest(page, size))

    @GetMapping("/{itemId}")
    fun getItem(@AuthenticationPrincipal user: CurrentUser, @PathVariable ownerId: Long, @PathVariable itemId: Long) =
        wardrobe.getItem(user.id, ownerId, itemId)

    @GetMapping("/{itemId}/photos")
    fun listPhotos(@AuthenticationPrincipal user: CurrentUser, @PathVariable ownerId: Long, @PathVariable itemId: Long) =
        wardrobe.listPhotos(user.id, ownerId, itemId)

    @GetMapping("/{itemId}/photos/{photoId}/content")
    fun getPhotoContent(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable itemId: Long,
        @PathVariable photoId: Long,
    ) = wardrobe.getPhotoContent(user.id, ownerId, itemId, photoId).toPhotoContentResponse()

    @DeleteMapping("/{itemId}/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePhoto(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable itemId: Long,
        @PathVariable photoId: Long,
    ) = wardrobe.deletePhoto(user.id, ownerId, itemId, photoId)
}
