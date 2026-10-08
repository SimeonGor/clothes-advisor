package ru.itmo.clothesadvisor.controller.access

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.service.access.AccessGrantService

@RestController
@RequestMapping("/api/me/stylist-access")
@PreAuthorize("hasRole('USER')")
internal class StylistAccessController(private val access: AccessGrantService) {
    @PutMapping("/{stylistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun grant(@AuthenticationPrincipal user: CurrentUser, @PathVariable stylistId: Long) =
        access.grant(user.id, stylistId)

    @DeleteMapping("/{stylistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal user: CurrentUser, @PathVariable stylistId: Long) =
        access.revoke(user.id, stylistId)

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = access.listRecipients(user.id, toPageRequest(page, size))
}
