package ru.itmo.clothesadvisor.accountservice.service.user

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.dto.user.AdminUserResponse
import ru.itmo.clothesadvisor.accountservice.dto.user.UpdateUserRoleAndStatusRequest
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@Service
@Transactional(readOnly = true)
internal class AdminUserService(
    private val users: AppUserRepository,
    private val appUsers: AppUserService,
) {
    fun list(page: PageRequest): Mono<Page<AdminUserResponse>> = Mono.defer {
        users.findPage(page.pageSize, page.offset.toInt()).map { it.toResponse() }
            .collectList()
            .flatMap { content -> users.count().map { total -> PageImpl(content, page, total) } }
    }

    fun get(id: Long): Mono<AdminUserResponse> = Mono.defer {
        users.findById(id)
            .switchIfEmpty(Mono.error(EntityNotFoundException("User $id not found")))
            .map { it.toResponse() }
    }

    @Transactional
    fun update(
        actorId: Long,
        id: Long,
        request: UpdateUserRoleAndStatusRequest,
    ): Mono<AdminUserResponse> = Mono.defer {
        users.findLockedRows(actorId, id).collectList().flatMap { locked ->
            val actor = locked.find { it.id == actorId }
                ?: return@flatMap Mono.error(EntityNotFoundException("Actor not found"))
            if (actor.role != UserRole.ADMIN || actor.status != UserStatus.ACTIVE) {
                return@flatMap Mono.error(AccessDeniedException())
            }
            if (
                actorId == id &&
                (request.role != UserRole.ADMIN || request.status != UserStatus.ACTIVE)
            ) {
                return@flatMap Mono.error(AdminUserConflictException())
            }
            appUsers.changeRoleAndStatus(id, request.version, request.role, request.status)
                .map { it.toResponse() }
        }
    }

    private fun AppUser.toResponse() =
        AdminUserResponse(id, login, role, status, version, createdAt, modifiedAt)
}

internal class AdminUserConflictException : RuntimeException()
