package ru.itmo.clothesadvisor.controller.outfit

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.service.outfit.OutfitService

@RestController
@RequestMapping("/api/stylist/clients/{ownerId}/outfits")
@PreAuthorize("hasRole('STYLIST')")
internal class StylistOutfitController(private val outfits: OutfitService) {
    @PostMapping
    fun create(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @Valid @RequestBody request: CreateOutfitRequest,
    ) = outfits.createForClient(user.id, ownerId, request).let {
        ResponseEntity.created(URI("/api/stylist/clients/$ownerId/outfits/${it.id}")).body(it)
    }

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = outfits.listForClient(user.id, ownerId, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal user: CurrentUser, @PathVariable ownerId: Long, @PathVariable id: Long) =
        outfits.getForClient(user.id, ownerId, id)
}
