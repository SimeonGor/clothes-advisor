package ru.itmo.clothesadvisor.accountservice.service.user

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.dto.user.CreateUserRequest
import ru.itmo.clothesadvisor.accountservice.dto.user.SetUserPasswordRequest
import ru.itmo.clothesadvisor.accountservice.service.auth.PasswordService

// Hash before entering the transactional administrative service.
@Service
internal class UserManagementService(
    private val passwords: PasswordService,
    private val admins: AdminUserService,
) {
    fun create(actorId: Long, request: CreateUserRequest) =
        passwords.encode(request.password).flatMap { hash ->
            admins.create(actorId, request.login, hash, request.role)
        }

    fun setPassword(actorId: Long, id: Long, request: SetUserPasswordRequest) =
        passwords.encode(request.password).flatMap { hash ->
            admins.setPassword(actorId, id, request.version, hash)
        }

    fun bootstrap(login: String, password: String): Mono<Void> = Mono.defer {
        if (login.isBlank() && password.isBlank()) return@defer Mono.empty()
        if (login.isBlank() || login.length > 100 || password.isBlank() || password.length !in 12..128) {
            return@defer Mono.error(BootstrapAdminException())
        }
        passwords.encode(password).flatMap { hash -> admins.bootstrap(login, hash) }
    }.onErrorMap { BootstrapAdminException() }
}

internal class BootstrapAdminException : RuntimeException("Initial administrator setup failed")
