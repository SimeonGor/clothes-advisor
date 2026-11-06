package ru.itmo.clothesadvisor.service.user

import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.user.UserStatus

@Service
internal class CurrentUserService(private val accounts: BlockingAccountClient) {
    fun requireActive(id: Long): CurrentUser = accounts.getUser(id).toActiveCurrentUser(id)
}

internal fun AccountUserResponse.toActiveCurrentUser(id: Long): CurrentUser {
    if (status == UserStatus.BLOCKED) {
        throw AccessDeniedException()
    }
    return CurrentUser(id, login, role)
}
