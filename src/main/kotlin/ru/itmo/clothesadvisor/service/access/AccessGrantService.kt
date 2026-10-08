package ru.itmo.clothesadvisor.service.access

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.access.AccessRecipientResponse
import ru.itmo.clothesadvisor.dto.access.StylistClientResponse
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.access.AccessGrantRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

@Service
@Transactional(readOnly = true)
internal class AccessGrantService(private val grants: AccessGrantRepository, private val users: AppUserService) {
    @Transactional
    fun grant(ownerId: Long, stylistId: Long) {
        val stylist = users.findById(stylistId)
        if (stylist?.role != UserRole.STYLIST || stylist.status != UserStatus.ACTIVE) throw EntityNotFoundException()
        grants.grant(ownerId, stylistId)
    }

    @Transactional
    fun revoke(ownerId: Long, stylistId: Long) {
        grants.revoke(ownerId, stylistId)
    }

    fun listRecipients(ownerId: Long, pageable: Pageable): List<AccessRecipientResponse> =
        grants.findRecipients(ownerId, pageable).map { AccessRecipientResponse(requireNotNull(it.id), it.login) }

    fun listClients(stylistId: Long, pageable: Pageable): List<StylistClientResponse> =
        grants.findClients(stylistId, pageable).map { StylistClientResponse(requireNotNull(it.id), it.login) }

    fun requireAccess(stylistId: Long, ownerId: Long) {
        if (!grants.hasActiveAccess(stylistId, ownerId)) throw EntityNotFoundException()
    }
}
