package ru.itmo.clothesadvisor.accountservice.model.user

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

internal enum class UserRole {
    USER,
    STYLIST,
    ADMIN;

    companion object {
        @JvmStatic @JsonCreator fun fromValue(value: String): UserRole = valueOf(value)
    }
}

internal enum class UserStatus {
    ACTIVE,
    BLOCKED;

    companion object {
        @JvmStatic @JsonCreator fun fromValue(value: String): UserStatus = valueOf(value)
    }
}

@Table("app_user")
internal class AppUser(
    @Id val id: Long,
    @field:NotBlank @field:Size(max = 100) val login: String,
    @get:JsonIgnore @field:NotBlank val passwordHash: String,
    val role: UserRole,
    val status: UserStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
