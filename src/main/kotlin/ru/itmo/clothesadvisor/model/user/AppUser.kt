package ru.itmo.clothesadvisor.model.user

import com.fasterxml.jackson.annotation.JsonCreator
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

internal enum class UserRole {
    USER, STYLIST, ADMIN;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): UserRole = valueOf(value)
    }
}

internal enum class UserStatus {
    ACTIVE, BLOCKED;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): UserStatus = valueOf(value)
    }
}

internal class AppUser(
    login: String,
    passwordHash: String,
    role: UserRole,
    status: UserStatus,
    now: Instant,
) {
    var id: Long? = null
        internal set

    @field:NotBlank
    @field:Size(max = 100)
    var login: String = login
        internal set

    @field:NotBlank
    var passwordHash: String = passwordHash
        internal set

    var role: UserRole = role
        internal set

    var status: UserStatus = status
        internal set

    var version: Long = 1
        internal set

    var createdAt: Instant = now
        internal set

    var modifiedAt: Instant = now
        internal set

    internal fun changeRoleAndStatus(role: UserRole, status: UserStatus) {
        this.role = role
        this.status = status
    }
}
