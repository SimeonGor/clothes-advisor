package ru.itmo.clothesadvisor.model.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

internal enum class UserRole { USER, STYLIST, ADMIN }

internal enum class UserStatus { ACTIVE, BLOCKED }

@Entity
@Table(name = "app_user")
internal class AppUser(
    login: String,
    passwordHash: String,
    role: UserRole,
    status: UserStatus,
    now: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:NotBlank
    @field:Column(nullable = false, unique = true, columnDefinition = "text")
    var login: String = login
        protected set

    @field:NotBlank
    @field:Column(name = "password_hash", nullable = false, columnDefinition = "text")
    var passwordHash: String = passwordHash
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(nullable = false, columnDefinition = "user_role")
    var role: UserRole = role
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(nullable = false, columnDefinition = "user_status")
    var status: UserStatus = status
        protected set

    @field:Version
    @field:Column(nullable = false)
    var version: Long = 1
        protected set

    @field:Column(name = "created_at", nullable = false)
    var createdAt: Instant = now
        protected set

    @field:Column(name = "modified_at", nullable = false)
    var modifiedAt: Instant = now
        protected set

    internal fun changeRoleAndStatus(role: UserRole, status: UserStatus, now: Instant) {
        this.role = role
        this.status = status
        modifiedAt = now
    }
}
