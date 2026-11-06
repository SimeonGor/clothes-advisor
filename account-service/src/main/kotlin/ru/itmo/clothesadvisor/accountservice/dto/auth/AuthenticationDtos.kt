package ru.itmo.clothesadvisor.accountservice.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole

internal class LoginRequest(
    @field:NotBlank @field:Size(max = 100) val login: String,
    @field:NotBlank @field:Size(max = 128) val password: String,
)

internal class LoginResponse(val accessToken: String, val expiresIn: Long) {
    val tokenType = "Bearer"
}

internal data class CurrentAccountResponse(val id: Long, val login: String, val role: UserRole)
