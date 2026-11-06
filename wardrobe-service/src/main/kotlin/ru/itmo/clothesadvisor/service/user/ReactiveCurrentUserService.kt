package ru.itmo.clothesadvisor.service.user

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException

@Service
internal class ReactiveCurrentUserService(private val accounts: AccountClient) {
    fun requireActive(id: Long): Mono<CurrentUser> =
        accounts.getUser(id)
            .switchIfEmpty(Mono.error(AccountUnavailableException()))
            .map { it.toActiveCurrentUser(id) }
}
