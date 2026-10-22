package ru.itmo.clothesadvisor.accountservice.service.user

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

@Service
internal class CurrentUserService(private val users: AppUserService) {
    fun requireActive(id: Long): Mono<AppUser> = Mono.defer {
        users.findById(id)
            .switchIfEmpty(Mono.error(EntityNotFoundException("Actor not found")))
            .flatMap { user ->
                if (user.status == UserStatus.BLOCKED) Mono.error(AccessDeniedException())
                else Mono.just(user)
            }
    }
}
