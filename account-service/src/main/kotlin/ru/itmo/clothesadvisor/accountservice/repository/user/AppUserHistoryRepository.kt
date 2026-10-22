package ru.itmo.clothesadvisor.accountservice.repository.user

import java.time.Instant
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.Repository
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.user.AppUserHistory
import ru.itmo.clothesadvisor.accountservice.model.user.AppUserHistoryId
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

internal interface AppUserHistoryRepository : Repository<AppUserHistory, AppUserHistoryId> {
    fun insert(history: AppUserHistory): Mono<AppUserHistory> =
        insert(
            history.id.userId,
            history.id.version,
            history.login,
            history.role,
            history.status,
            history.modifiedAt,
        )

    @Query(
        """
        INSERT INTO app_user_history (user_id, version, login, role, status, modified_at)
        VALUES (:userId, :version, :login, :role::user_role, :status::user_status, :modifiedAt)
        RETURNING *
        """,
    )
    fun insert(
        userId: Long,
        version: Long,
        login: String,
        role: UserRole,
        status: UserStatus,
        modifiedAt: Instant,
    ): Mono<AppUserHistory>
}
