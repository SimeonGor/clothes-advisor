package ru.itmo.clothesadvisor.accountservice.dto.user

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

internal class CreateUserRequest(
    @field:NotBlank @field:Size(max = 100) val login: String,
    @field:NotBlank @field:Size(min = 12, max = 128)
    @field:Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
    val password: String,
    val role: UserRole,
)

internal class SetUserPasswordRequest(
    @field:NotBlank @field:Size(min = 12, max = 128)
    @field:Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
    val password: String,
    @field:Positive val version: Long,
)

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
