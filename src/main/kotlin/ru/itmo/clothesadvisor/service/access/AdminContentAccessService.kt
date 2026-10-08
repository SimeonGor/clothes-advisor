package ru.itmo.clothesadvisor.service.access

import jakarta.persistence.EntityNotFoundException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

@Service
@Transactional(readOnly = true)
internal class AdminContentAccessService(private val users: AppUserRepository) {
    fun requireAccess(adminId: Long, ownerId: Long) {
        if (!users.existsByIdAndRoleAndStatus(adminId, UserRole.ADMIN, UserStatus.ACTIVE)) {
            throw AccessDeniedException("Active administrator required")
        }
        if (!users.existsById(ownerId)) throw EntityNotFoundException()
    }
}
