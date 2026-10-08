package ru.itmo.clothesadvisor.service.user

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import ru.itmo.clothesadvisor.model.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.user.AdminUserResponse
import ru.itmo.clothesadvisor.dto.user.UpdateUserRoleAndStatusRequest
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

@Service
@Transactional(readOnly = true)
internal class AdminUserService(private val users: AppUserRepository, private val appUsers: AppUserService) {
    fun list(page: PageRequest): Page<AdminUserResponse> =
        users.findAll(page.withSort(Sort.Direction.DESC, "id")).map { it.toResponse() }

    fun get(id: Long): AdminUserResponse =
        (users.findById(id) ?: throw EntityNotFoundException("User $id not found")).toResponse()

    @Transactional
    fun update(actorId: Long, id: Long, request: UpdateUserRoleAndStatusRequest): AdminUserResponse {
        val locked = users.findAllLockedByIdInOrderByIdAsc(listOf(actorId, id))
        val actor = locked.find { it.id == actorId }
        if (actor?.role != UserRole.ADMIN || actor.status != UserStatus.ACTIVE) {
            throw AccessDeniedException("Active administrator required")
        }
        if (actorId == id && (request.role != UserRole.ADMIN || request.status != UserStatus.ACTIVE)) {
            throw AdminUserConflictException()
        }
        return appUsers.changeRoleAndStatus(id, request.version, request.role, request.status).toResponse()
    }

    private fun AppUser.toResponse() =
        AdminUserResponse(requireNotNull(id), login, role, status, version, createdAt, modifiedAt)
}

internal class AdminUserConflictException : RuntimeException()
