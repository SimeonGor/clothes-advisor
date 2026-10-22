package ru.itmo.clothesadvisor.accountservice.dto.user

import jakarta.validation.constraints.Positive
import java.time.Instant
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

internal data class UpdateUserRoleAndStatusRequest(
    val role: UserRole,
    val status: UserStatus,
    @field:Positive val version: Long,
)

internal data class AdminUserResponse(
    val id: Long,
    val login: String,
    val role: UserRole,
    val status: UserStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
