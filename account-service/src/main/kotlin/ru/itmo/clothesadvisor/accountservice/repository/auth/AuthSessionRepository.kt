package ru.itmo.clothesadvisor.accountservice.repository.auth

import java.time.Instant
import java.util.UUID
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.Repository
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.auth.AuthSession
import ru.itmo.clothesadvisor.accountservice.model.auth.RefreshTokenState

internal interface AuthSessionRepository : Repository<AuthSession, UUID> {
    @Query("INSERT INTO auth_session (id, user_id, expires_at) VALUES (:id, :userId, :expiresAt) RETURNING *")
    fun create(id: UUID, userId: Long, expiresAt: Instant): Mono<AuthSession>

    @Query("""SELECT s.id AS session_id, s.user_id, s.expires_at, s.revoked_at, t.used_at
        FROM auth_refresh_token t JOIN auth_session s ON s.id = t.session_id
        WHERE t.token_hash = :hash""")
    fun findToken(hash: String): Mono<RefreshTokenState>

    @Modifying
    @Query("INSERT INTO auth_refresh_token (token_hash, session_id) VALUES (:hash, :sessionId)")
    fun insertToken(hash: String, sessionId: UUID): Mono<Int>

    @Modifying
    @Query("UPDATE auth_refresh_token SET used_at = CURRENT_TIMESTAMP WHERE token_hash = :hash")
    fun consumeToken(hash: String): Mono<Int>

    @Modifying
    @Query("UPDATE auth_session SET revoked_at = CURRENT_TIMESTAMP WHERE id = :id AND revoked_at IS NULL")
    fun revoke(id: UUID): Mono<Int>

    @Modifying
    @Query("UPDATE auth_session SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = :userId AND revoked_at IS NULL")
    fun revokeAll(userId: Long): Mono<Int>
}
