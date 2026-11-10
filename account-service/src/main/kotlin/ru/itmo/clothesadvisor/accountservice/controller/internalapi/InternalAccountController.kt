package ru.itmo.clothesadvisor.accountservice.controller.internalapi

import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.dto.user.InternalUserResponse
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.service.access.AccountAccessService
import ru.itmo.clothesadvisor.accountservice.service.user.AppUserService

@RestController
internal class InternalAccountController(
    private val users: AppUserService,
    private val access: AccountAccessService,
) : InternalAccountApi {
    override fun user(userId: Long): Mono<InternalUserResponse> =
        users.findById(userId)
            .switchIfEmpty(Mono.error(EntityNotFoundException("User not found")))
            .map { InternalUserResponse(it.id, it.login, it.role, it.status) }

    override fun stylistAccess(stylistId: Long, ownerId: Long): Mono<Void> =
        access.requireStylistAccess(stylistId, ownerId)

    override fun adminAccess(adminId: Long, ownerId: Long): Mono<Void> =
        access.requireAdminAccess(adminId, ownerId)
}
