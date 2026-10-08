package ru.itmo.clothesadvisor.repository.user

import java.sql.ResultSet
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

internal fun mapUser(row: ResultSet): AppUser =
    AppUser(row.getString("login"), row.getString("password_hash"), UserRole.valueOf(row.getString("role")),
        UserStatus.valueOf(row.getString("status")), row.getTimestamp("created_at").toInstant()).apply {
        id = row.getLong("id")
        version = row.getLong("version")
        modifiedAt = row.getTimestamp("modified_at").toInstant()
    }

@Repository
internal class AppUserRepository(private val jdbc: JdbcTemplate) {
    fun lockActiveUserForShare(id: Long): Long? =
        jdbc.query("SELECT id FROM app_user WHERE id = ? AND role = 'USER' AND status = 'ACTIVE' FOR SHARE",
            { row, _ -> row.getLong("id") }, id).singleOrNull()

    fun existsById(id: Long): Boolean =
        jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE id = ?)", Boolean::class.java, id)!!

    fun existsByIdAndRoleAndStatus(id: Long, role: UserRole, status: UserStatus): Boolean =
        jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE id = ? AND role = ?::user_role AND status = ?::user_status)",
            Boolean::class.java, id, role.name, status.name)!!

    fun findById(id: Long): AppUser? = jdbc.query("SELECT * FROM app_user WHERE id = ?", { row, _ -> mapUser(row) }, id).singleOrNull()
    fun findByLogin(login: String): AppUser? = jdbc.query("SELECT * FROM app_user WHERE login = ?", { row, _ -> mapUser(row) }, login).singleOrNull()

    fun findAll(pageable: Pageable): Page<AppUser> {
        val rows = jdbc.query("SELECT * FROM app_user ORDER BY id DESC LIMIT ? OFFSET ?",
            { row, _ -> mapUser(row) }, pageable.pageSize, pageable.offset)
        return PageImpl(rows, pageable, jdbc.queryForObject("SELECT count(*) FROM app_user", Long::class.java)!!)
    }

    fun findAllLockedByIdInOrderByIdAsc(ids: Collection<Long>): List<AppUser> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query("SELECT * FROM app_user WHERE id IN (${ids.joinToString { "?" }}) ORDER BY id FOR UPDATE",
            { row, _ -> mapUser(row) }, *ids.toTypedArray())
    }

    fun create(login: String, passwordHash: String, role: UserRole, status: UserStatus): AppUser = jdbc.queryForObject("""
        INSERT INTO app_user (login, password_hash, role, status, version)
        VALUES (?, ?, ?::user_role, ?::user_status, 1) RETURNING *
    """, { row, _ -> mapUser(row) }, login, passwordHash, role.name, status.name)

    fun update(user: AppUser): AppUser = jdbc.query("""
        UPDATE app_user SET role = ?::user_role, status = ?::user_status, modified_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = ? AND version = ? RETURNING *
    """, { row, _ -> mapUser(row) }, user.role.name, user.status.name, user.id, user.version).singleOrNull()
        ?: throw OptimisticLockingFailureException("User ${user.id} version conflict")
}
