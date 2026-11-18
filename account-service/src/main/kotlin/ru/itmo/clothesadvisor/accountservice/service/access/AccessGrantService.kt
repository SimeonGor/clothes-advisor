package ru.itmo.clothesadvisor.accountservice.service.access

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.observability.DurableTrace
import ru.itmo.clothesadvisor.accountservice.dto.access.AccessRecipientResponse
import ru.itmo.clothesadvisor.accountservice.dto.access.StylistClientResponse
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.access.AccessGrantRepository
import ru.itmo.clothesadvisor.accountservice.service.user.AppUserService
import ru.itmo.clothesadvisor.accountservice.service.user.CurrentUserService

@Service
@Transactional(readOnly = true)
internal class AccessGrantService(
    private val grants: AccessGrantRepository,
    private val users: AppUserService,
    private val currentUser: CurrentUserService,
) {
    @Transactional
    fun grant(ownerId: Long, stylistId: Long): Mono<Void> = Mono.defer {
        currentUser.requireActive(ownerId).flatMap {
            users.findById(stylistId)
                .filter { it.role == UserRole.STYLIST && it.status == UserStatus.ACTIVE }
                .switchIfEmpty(Mono.error(EntityNotFoundException("Stylist not found")))
                .flatMap { grants.grant(ownerId, stylistId, DurableTrace.capture()) }
        }.then()
    }

    @Transactional
    fun revoke(ownerId: Long, stylistId: Long): Mono<Void> = Mono.defer {
        currentUser.requireActive(ownerId).flatMap { grants.revoke(ownerId, stylistId, DurableTrace.capture()) }.then()
    }

    fun listRecipients(ownerId: Long, page: PageRequest): Mono<List<AccessRecipientResponse>> =
        Mono.defer {
            currentUser.requireActive(ownerId).flatMap {
                grants.findRecipients(ownerId, page.pageSize, page.offset.toInt())
                    .map { AccessRecipientResponse(it.id, it.login) }.collectList()
            }
        }

    fun listClients(stylistId: Long, page: PageRequest): Mono<List<StylistClientResponse>> =
        Mono.defer {
            currentUser.requireActive(stylistId).flatMap {
                grants.findClients(stylistId, page.pageSize, page.offset.toInt())
                    .map { StylistClientResponse(it.id, it.login) }.collectList()
            }
        }
}
