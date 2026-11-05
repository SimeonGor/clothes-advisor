package ru.itmo.clothesadvisor.accountservice.repository.user

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.access.AdminAccessState
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

internal interface AppUserRepository : Repository<AppUser, Long> {
    fun findById(id: Long): Mono<AppUser>

    @Query("SELECT * FROM app_user WHERE id = :id FOR UPDATE")
    fun findByIdForUpdate(id: Long): Mono<AppUser>

    fun findByLogin(login: String): Mono<AppUser>

    @Query("SELECT * FROM app_user WHERE login = :login FOR UPDATE")
    fun findByLoginForUpdate(login: String): Mono<AppUser>

    @Query(
        """
        INSERT INTO app_user (login, password_hash, role, status, version)
        VALUES (:login, :passwordHash, 'ADMIN', 'ACTIVE', 1)
        ON CONFLICT (login) DO NOTHING RETURNING *
        """,
    )
    fun createBootstrapAdmin(login: String, passwordHash: String): Mono<AppUser>

    @Query("""SELECT * FROM app_user WHERE role = 'STYLIST' AND status = 'ACTIVE'
        ORDER BY id LIMIT :limit OFFSET :offset""")
    fun findActiveStylists(limit: Int, offset: Int): Flux<AppUser>

    @Query("SELECT * FROM app_user ORDER BY id DESC LIMIT :limit OFFSET :offset")
    fun findPage(limit: Int, offset: Int): Flux<AppUser>

    @Query("SELECT count(*) FROM app_user")
    fun count(): Mono<Long>

    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM app_user WHERE id = :adminId AND role = 'ADMIN' AND status = 'ACTIVE'
        ) AS admin_allowed, EXISTS (
            SELECT 1 FROM app_user WHERE id = :ownerId
        ) AS owner_exists
        """,
    )
    fun findAdminAccessState(adminId: Long, ownerId: Long): Mono<AdminAccessState>

    @Query("SELECT * FROM app_user WHERE id IN (:actorId, :targetId) ORDER BY id FOR UPDATE")
    fun findLockedRows(actorId: Long, targetId: Long): Flux<AppUser>

    @Query(
        """
        INSERT INTO app_user (login, password_hash, role, status, version)
        VALUES (:login, :passwordHash, :role::user_role, :status::user_status, 1) RETURNING *
        """,
    )
    fun create(
        login: String,
        passwordHash: String,
        role: UserRole,
        status: UserStatus,
    ): Mono<AppUser>

    @Query(
        """
        UPDATE app_user SET role = :role::user_role, status = :status::user_status,
            modified_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = :id AND version = :version RETURNING *
        """,
    )
    fun updateRow(id: Long, version: Long, role: UserRole, status: UserStatus): Mono<AppUser>

    @Query(
        """
        UPDATE app_user SET password_hash = :passwordHash,
            modified_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = :id AND version = :version RETURNING *
        """,
    )
    fun updatePassword(id: Long, version: Long, passwordHash: String): Mono<AppUser>
}
