package ru.itmo.clothesadvisor.repository.user

import java.time.Instant
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.user.AppUserHistory
import ru.itmo.clothesadvisor.model.user.AppUserHistoryId
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

internal interface AppUserHistoryRepository : Repository<AppUserHistory, AppUserHistoryId> {
    fun insert(history: AppUserHistory) = insert(
        history.id.userId, history.id.version, history.login, history.role, history.status, history.modifiedAt,
    )

    @Modifying
    @Query("""
        INSERT INTO app_user_history (user_id, version, login, role, status, modified_at)
        VALUES (:userId, :version, :login, :role::user_role, :status::user_status, :modifiedAt)
    """)
    fun insert(userId: Long, version: Long, login: String, role: UserRole, status: UserStatus, modifiedAt: Instant)
}
