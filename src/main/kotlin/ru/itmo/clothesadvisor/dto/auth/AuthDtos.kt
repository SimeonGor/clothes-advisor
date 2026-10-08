package ru.itmo.clothesadvisor.dto.auth

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import ru.itmo.clothesadvisor.model.user.UserRole

internal class LoginRequest(
    @field:NotBlank val login: String,
    @field:Schema(description = "Непустой пароль, не более 72 байт UTF-8 (не символов)", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    @field:NotBlank val password: String,
)

internal data class AccessTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)

internal data class CurrentUser(val id: Long, val login: String, val role: UserRole)
