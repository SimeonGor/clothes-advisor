package ru.itmo.clothesadvisor.controller.outfit

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
import ru.itmo.clothesadvisor.service.outfit.AdminOutfitService

@RestController
@RequestMapping("/api/admin/users/{ownerId}/outfits")
@PreAuthorize("hasRole('ADMIN')")
internal class AdminOutfitController(private val outfits: AdminOutfitService) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable ownerId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = outfits.list(user.id, ownerId, toPageRequest(page, size)).let {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal user: CurrentUser, @PathVariable ownerId: Long, @PathVariable id: Long) =
        outfits.get(user.id, ownerId, id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal user: CurrentUser, @PathVariable ownerId: Long, @PathVariable id: Long) =
        outfits.delete(user.id, ownerId, id)
}
