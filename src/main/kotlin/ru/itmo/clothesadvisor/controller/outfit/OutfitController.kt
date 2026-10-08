package ru.itmo.clothesadvisor.controller.outfit

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService

@RestController
@RequestMapping("/api/outfits")
@PreAuthorize("hasRole('USER')")
internal class OutfitController(private val outfits: OutfitService, private val ratings: OutfitRatingService) {
    @PostMapping
    fun create(@AuthenticationPrincipal user: CurrentUser, @Valid @RequestBody request: CreateOutfitRequest) =
        outfits.create(user.id, request).let { ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it) }

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = outfits.list(user.id, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal user: CurrentUser, @PathVariable id: Long) = outfits.get(user.id, id)

    @GetMapping("/{id}/ratings/history")
    fun history(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = ratings.history(user.id, id, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal user: CurrentUser, @PathVariable id: Long) = outfits.delete(user.id, id)
}
