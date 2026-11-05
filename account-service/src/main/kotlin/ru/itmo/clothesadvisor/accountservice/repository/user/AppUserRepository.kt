package ru.itmo.clothesadvisor.accountservice.repository.user

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.access.AdminAccessState
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

internal interface AppUserRepository : R2dbcRepository<AppUser, Long> {
    override fun findById(id: Long): Mono<AppUser>

    fun findByLogin(login: String): Mono<AppUser>

    @Query("SELECT * FROM app_user ORDER BY id DESC LIMIT :limit OFFSET :offset")
    fun findPage(limit: Int, offset: Int): Flux<AppUser>

    @Query("SELECT count(*) FROM app_user")
    override fun count(): Mono<Long>

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
}
