package ru.itmo.clothesadvisor.security.auth

internal object PasswordRules {
    fun isWithinBcryptLimit(password: String): Boolean = password.toByteArray(Charsets.UTF_8).size <= 72
}
