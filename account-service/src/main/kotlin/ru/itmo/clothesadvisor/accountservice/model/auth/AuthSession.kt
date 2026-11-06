package ru.itmo.clothesadvisor.accountservice.model.auth

import java.time.Instant
import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("auth_session")
internal class AuthSession(
    @Id val id: UUID,
    val userId: Long,
    val expiresAt: Instant,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

internal class RefreshTokenState(
    val sessionId: UUID,
    val userId: Long,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val usedAt: Instant?,
)
