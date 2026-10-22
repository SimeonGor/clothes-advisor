package ru.itmo.clothesadvisor.accountservice.dto.user

import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

internal data class InternalUserResponse(
    val id: Long,
    val login: String,
    val role: UserRole,
    val status: UserStatus,
)
