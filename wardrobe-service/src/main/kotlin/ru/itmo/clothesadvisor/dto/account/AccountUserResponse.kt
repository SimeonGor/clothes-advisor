package ru.itmo.clothesadvisor.dto.account

import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

internal data class AccountUserResponse(
    val id: Long,
    val login: String,
    val role: UserRole,
    val status: UserStatus,
)
