package ru.itmo.clothesadvisor.accountservice.controller.internalapi

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.dto.user.InternalUserResponse
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.service.access.AccountAccessService
import ru.itmo.clothesadvisor.accountservice.service.user.AppUserService

@Hidden
@RestController
@RequestMapping("/internal")
internal class InternalAccountController(
    private val users: AppUserService,
    private val access: AccountAccessService,
) {
    @GetMapping("/users/{userId}")
    fun user(@PathVariable userId: Long): Mono<InternalUserResponse> =
        users.findById(userId)
            .switchIfEmpty(Mono.error(EntityNotFoundException("User not found")))
            .map { InternalUserResponse(it.id, it.login, it.role, it.status) }

    @GetMapping("/stylist-access/{stylistId}/clients/{ownerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun stylistAccess(@PathVariable stylistId: Long, @PathVariable ownerId: Long): Mono<Void> =
        access.requireStylistAccess(stylistId, ownerId)

    @GetMapping("/admin-access/{adminId}/users/{ownerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun adminAccess(@PathVariable adminId: Long, @PathVariable ownerId: Long): Mono<Void> =
        access.requireAdminAccess(adminId, ownerId)
}
