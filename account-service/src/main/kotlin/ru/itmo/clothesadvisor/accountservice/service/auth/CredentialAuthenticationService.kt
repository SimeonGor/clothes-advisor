package ru.itmo.clothesadvisor.accountservice.service.auth

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@Service
internal class CredentialAuthenticationService(
    private val users: AppUserRepository,
    private val passwords: PasswordService,
) {
    fun authenticate(login: String, password: String): Mono<AppUser> = Mono.defer {
        users.findByLogin(login)
            .flatMap { user ->
                passwords.matches(password, user.passwordHash)
                    .flatMap { matched ->
                        if (matched && user.status == UserStatus.ACTIVE) {
                            Mono.just(user)
                        } else {
                            Mono.error(InvalidCredentialsException())
                        }
                    }
            }
            .switchIfEmpty(
                Mono.defer {
                    passwords.matches(password, null)
                        .then(Mono.error<AppUser>(InvalidCredentialsException()))
                },
            )
    }
}
