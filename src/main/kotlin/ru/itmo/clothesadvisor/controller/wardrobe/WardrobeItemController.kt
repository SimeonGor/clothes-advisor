package ru.itmo.clothesadvisor.controller.wardrobe

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
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeManagementService

@RestController
@RequestMapping("/api/wardrobe/items")
@PreAuthorize("hasRole('USER')")
internal class WardrobeItemController(
    private val items: WardrobeItemService,
    private val management: WardrobeManagementService,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: CreateWardrobeItemRequest,
    ): ResponseEntity<WardrobeItemResponse> {
        val created = items.create(user.id, request)
        return ResponseEntity.created(URI("/api/wardrobe/items/${created.id}")).body(created)
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createWithPhotos(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestPart("item") request: CreateWardrobeItemRequest,
        @RequestPart("photos", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<WardrobeItemResponse> {
        val created = management.createWithPhotos(user.id, request, files.orEmpty())
        return ResponseEntity.created(URI("/api/wardrobe/items/${created.id}")).body(created)
    }

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): List<WardrobeItemResponse> = items.list(user.id, toPageRequest(page, size))

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal user: CurrentUser, @PathVariable id: Long): WardrobeItemResponse =
        items.get(user.id, id)

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateWardrobeItemRequest,
    ): WardrobeItemResponse = items.update(user.id, id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable id: Long,
        @RequestParam @Positive version: Long,
    ) = items.delete(user.id, id, version)
}
