package ru.itmo.clothesadvisor.accountservice.service.user

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.AppUserHistory
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserHistoryRepository
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@Service
@Transactional(readOnly = true)
internal class AppUserService(
    private val users: AppUserRepository,
    private val history: AppUserHistoryRepository,
    private val validator: Validator,
) {
    @Transactional
    fun create(
        login: String,
        passwordHash: String,
        role: UserRole,
        status: UserStatus = UserStatus.ACTIVE,
    ): Mono<AppUser> = Mono.defer {
        val violations =
            validator.validateValue(AppUser::class.java, "login", login) +
                validator.validateValue(AppUser::class.java, "passwordHash", passwordHash)
        if (violations.isNotEmpty()) {
            return@defer Mono.error(ConstraintViolationException(violations))
        }
        users.create(login, passwordHash, role, status)
    }

    fun findById(id: Long): Mono<AppUser> = Mono.defer { users.findById(id) }

    fun findByLogin(login: String): Mono<AppUser> = Mono.defer { users.findByLogin(login) }

    @Transactional
    fun changeRoleAndStatus(
        id: Long,
        expectedVersion: Long,
        role: UserRole,
        status: UserStatus,
    ): Mono<AppUser> = Mono.defer {
        users.findById(id)
            .switchIfEmpty(Mono.error(EntityNotFoundException("User $id not found")))
            .flatMap { user ->
                if (user.version != expectedVersion) {
                    return@flatMap Mono.error(
                        OptimisticLockingFailureException("User $id version conflict"),
                    )
                }
                if (user.role == role && user.status == status) {
                    return@flatMap Mono.just(user)
                }

                users.updateRow(id, expectedVersion, role, status)
                    .switchIfEmpty(
                        Mono.error(OptimisticLockingFailureException("User $id version conflict")),
                    )
                    .flatMap { saved -> history.insert(AppUserHistory(user)).thenReturn(saved) }
            }
    }
}
