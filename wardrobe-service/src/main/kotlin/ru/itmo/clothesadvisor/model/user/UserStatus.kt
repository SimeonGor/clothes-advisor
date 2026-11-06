package ru.itmo.clothesadvisor.model.user

import com.fasterxml.jackson.annotation.JsonCreator

internal enum class UserStatus {
    ACTIVE,
    BLOCKED;

    companion object {
        @JvmStatic @JsonCreator fun fromValue(value: String): UserStatus = valueOf(value)
    }
}
