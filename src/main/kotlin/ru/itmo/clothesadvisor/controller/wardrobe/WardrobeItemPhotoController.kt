package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

@RestController
@RequestMapping("/api/wardrobe/items/{itemId}/photos")
@PreAuthorize("hasRole('USER')")
internal class WardrobeItemPhotoController(private val photos: WardrobeItemPhotoService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable itemId: Long,
        @RequestPart("photos") files: List<MultipartFile>,
    ): List<WardrobeItemPhotoResponse> = photos.add(user.id, itemId, files)

    @GetMapping
    fun list(@AuthenticationPrincipal user: CurrentUser, @PathVariable itemId: Long) = photos.list(user.id, itemId)

    @GetMapping("/{photoId}/content")
    fun content(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable itemId: Long,
        @PathVariable photoId: Long,
    ) = photos.getContent(user.id, itemId, photoId).toPhotoContentResponse()

    @DeleteMapping("/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal user: CurrentUser, @PathVariable itemId: Long, @PathVariable photoId: Long) =
        photos.delete(user.id, itemId, photoId)
}
