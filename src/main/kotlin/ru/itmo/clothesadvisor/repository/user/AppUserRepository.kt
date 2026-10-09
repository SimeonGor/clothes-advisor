package ru.itmo.clothesadvisor.repository.user

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

internal interface AppUserRepository : Repository<AppUser, Long> {
    @Query("SELECT id FROM app_user WHERE id = :id AND role = 'USER' AND status = 'ACTIVE' FOR SHARE")
    fun lockActiveUserForShare(id: Long): Long?

    fun existsById(id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM app_user WHERE id = :id AND role = :role::user_role AND status = :status::user_status)")
    fun existsByIdAndRoleAndStatus(id: Long, role: UserRole, status: UserStatus): Boolean

    fun findById(id: Long): AppUser?
    fun findByLogin(login: String): AppUser?

    fun findAllByOrderByIdDesc(pageable: Pageable): Page<AppUser> =
        PageImpl(findPage(pageable.pageSize, pageable.offset), pageable, count())

    @Query("SELECT * FROM app_user ORDER BY id DESC LIMIT :limit OFFSET :offset")
    fun findPage(limit: Int, offset: Long): List<AppUser>

    fun count(): Long

    fun findAllLockedByIdInOrderByIdAsc(ids: Collection<Long>): List<AppUser> =
        if (ids.isEmpty()) emptyList() else findLockedRows(ids)

    @Query("SELECT * FROM app_user WHERE id IN (:ids) ORDER BY id FOR UPDATE")
    fun findLockedRows(ids: Collection<Long>): List<AppUser>

    @Query("""
        INSERT INTO app_user (login, password_hash, role, status, version)
        VALUES (:login, :passwordHash, :role::user_role, :status::user_status, 1) RETURNING *
    """)
    fun create(login: String, passwordHash: String, role: UserRole, status: UserStatus): AppUser

    fun update(user: AppUser): AppUser =
        updateRow(requireNotNull(user.id), user.version, user.role, user.status)
            ?: throw OptimisticLockingFailureException("User ${user.id} version conflict")

    @Query("""
        UPDATE app_user SET role = :role::user_role, status = :status::user_status,
            modified_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = :id AND version = :version RETURNING *
    """)
    fun updateRow(id: Long, version: Long, role: UserRole, status: UserStatus): AppUser?
}
