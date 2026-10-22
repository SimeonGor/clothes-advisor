package ru.itmo.clothesadvisor.service.user

import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.user.UserStatus

@Service
internal class CurrentUserService(private val accounts: AccountClient) {
    fun requireActive(id: Long): CurrentUser {
        val user = accounts.getUser(id)
        if (user.status == UserStatus.BLOCKED) {
            throw AccessDeniedException()
        }
        return CurrentUser(id, user.login, user.role)
    }
}
