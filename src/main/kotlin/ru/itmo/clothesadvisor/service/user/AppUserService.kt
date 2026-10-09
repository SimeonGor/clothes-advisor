package ru.itmo.clothesadvisor.service.user

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.dao.OptimisticLockingFailureException
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
    private val validator: Validator,
) {
    @Transactional
    fun create(
        login: String,
        passwordHash: String,
        role: UserRole,
        status: UserStatus = UserStatus.ACTIVE,
    ): AppUser {
        val violations = validator.validateValue(AppUser::class.java, "login", login) +
            validator.validateValue(AppUser::class.java, "passwordHash", passwordHash)
        if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
        return users.create(login, passwordHash, role, status)
    }

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
            throw OptimisticLockingFailureException("User $id version conflict")
        }
        if (user.role == role && user.status == status) return user

        val previous = AppUserHistory(user)
        user.changeRoleAndStatus(role, status)
        val saved = users.update(user)
        history.insert(previous)
        return saved
    }
}
