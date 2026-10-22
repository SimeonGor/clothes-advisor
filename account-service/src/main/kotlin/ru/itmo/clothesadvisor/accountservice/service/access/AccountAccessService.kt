package ru.itmo.clothesadvisor.accountservice.service.access

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.repository.access.AccessGrantRepository
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@Service
internal class AccountAccessService(
    private val users: AppUserRepository,
    private val grants: AccessGrantRepository,
) {
    fun requireAdminAccess(adminId: Long, ownerId: Long): Mono<Void> = Mono.defer {
        users.findAdminAccessState(adminId, ownerId).flatMap { state ->
            when {
                !state.adminAllowed -> Mono.error(AccessDeniedException())
                !state.ownerExists -> Mono.error(EntityNotFoundException("Owner not found"))
                else -> Mono.empty()
            }
        }
    }

    fun requireStylistAccess(stylistId: Long, ownerId: Long): Mono<Void> = Mono.defer {
        grants.hasActiveAccess(stylistId, ownerId).flatMap { allowed ->
            if (allowed) Mono.empty()
            else Mono.error(EntityNotFoundException("Stylist access not found"))
        }
    }
}
