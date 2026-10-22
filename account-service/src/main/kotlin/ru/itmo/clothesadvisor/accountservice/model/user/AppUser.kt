package ru.itmo.clothesadvisor.accountservice.model.user

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

internal enum class UserRole {
    USER,
    STYLIST,
    ADMIN,
}

internal enum class UserStatus {
    ACTIVE,
    BLOCKED,
}

@Table("app_user")
internal class AppUser(
    @Id val id: Long,
    @field:NotBlank @field:Size(max = 100) val login: String,
    @field:NotBlank val passwordHash: String,
    val role: UserRole,
    val status: UserStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
