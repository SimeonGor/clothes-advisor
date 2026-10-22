package ru.itmo.clothesadvisor.accountservice.repository.user

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.Repository
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus

internal interface AppUserRepository : Repository<AppUser, Long> {
    fun findById(id: Long): Mono<AppUser>

    fun findByLogin(login: String): Mono<AppUser>

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
