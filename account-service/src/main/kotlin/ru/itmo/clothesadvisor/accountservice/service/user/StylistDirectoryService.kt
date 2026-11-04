package ru.itmo.clothesadvisor.accountservice.service.user

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.accountservice.dto.access.AccessRecipientResponse
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@Service
internal class StylistDirectoryService(private val users: AppUserRepository) {
    fun list(pageable: Pageable) = users.findActiveStylists(pageable.pageSize, pageable.offset.toInt())
        .map { AccessRecipientResponse(it.id, it.login) }
}
