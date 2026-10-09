package ru.itmo.clothesadvisor.model.user

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal data class AppUserHistoryId(
    val userId: Long,
    val version: Long,
)

@Table("app_user_history")
internal class AppUserHistory @PersistenceCreator constructor(
    @Id @Embedded.Empty val id: AppUserHistoryId,
    val login: String,
    val role: UserRole,
    val status: UserStatus,
    val modifiedAt: Instant,
) {
    constructor(user: AppUser) : this(
        AppUserHistoryId(requireNotNull(user.id), user.version),
        user.login, user.role, user.status, user.modifiedAt,
    )
}
