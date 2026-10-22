package ru.itmo.clothesadvisor.model.user

import com.fasterxml.jackson.annotation.JsonCreator

internal enum class UserRole {
    USER,
    STYLIST,
    ADMIN;

    companion object {
        @JvmStatic @JsonCreator fun fromValue(value: String): UserRole = valueOf(value)
    }
}
