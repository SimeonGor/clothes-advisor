package ru.itmo.clothesadvisor.service.user

import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.AppUserHistory
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserHistoryRepository
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

@Service
@Transactional(readOnly = true)
internal class AppUserService(
    private val users: AppUserRepository,
    private val history: AppUserHistoryRepository,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        login: String,
        passwordHash: String,
        role: UserRole,
        status: UserStatus = UserStatus.ACTIVE,
    ): AppUser = users.save(AppUser(login, passwordHash, role, status, clock.instant()))

    fun findById(id: Long): AppUser? = users.findById(id)

    fun findByLogin(login: String): AppUser? = users.findByLogin(login)

    @Transactional
    fun changeRoleAndStatus(
        id: Long,
        expectedVersion: Long,
        role: UserRole,
        status: UserStatus,
    ): AppUser {
        val user = users.findById(id) ?: throw EntityNotFoundException("User $id not found")
        if (user.version != expectedVersion) {
            throw ObjectOptimisticLockingFailureException(AppUser::class.java, id)
        }
        if (user.role == role && user.status == status) return user

        val timestamp = clock.instant()
        val previous = AppUserHistory(user, timestamp)
        user.changeRoleAndStatus(role, status, timestamp)
        users.flush()
        history.save(previous)
        return user
    }
}
