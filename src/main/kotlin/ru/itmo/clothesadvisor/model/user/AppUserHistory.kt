package ru.itmo.clothesadvisor.model.user

import java.time.Instant

internal data class AppUserHistoryId(
    val userId: Long,
    val version: Long,
)

internal class AppUserHistory(user: AppUser) {
    private val key = AppUserHistoryId(requireNotNull(user.id), user.version)

    val login: String = user.login

    val role: UserRole = user.role

    val status: UserStatus = user.status

    val modifiedAt: Instant = user.modifiedAt

    val id: AppUserHistoryId get() = key

}
