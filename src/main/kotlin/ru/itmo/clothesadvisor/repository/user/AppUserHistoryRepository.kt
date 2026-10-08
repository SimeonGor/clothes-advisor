package ru.itmo.clothesadvisor.repository.user

import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.user.AppUserHistory

@Repository
internal class AppUserHistoryRepository(private val jdbc: JdbcTemplate) {
    fun save(history: AppUserHistory) {
        jdbc.update("""
            INSERT INTO app_user_history (user_id, version, login, role, status, modified_at)
            VALUES (?, ?, ?, ?::user_role, ?::user_status, ?)
        """, history.id.userId, history.id.version, history.login, history.role.name, history.status.name,
            Timestamp.from(history.modifiedAt))
    }
}
