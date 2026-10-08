package ru.itmo.clothesadvisor.dto.user

import ru.itmo.clothesadvisor.model.user.UserRole

internal data class CurrentUser(val id: Long, val login: String, val role: UserRole)
