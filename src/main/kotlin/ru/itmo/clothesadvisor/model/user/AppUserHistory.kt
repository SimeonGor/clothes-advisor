package ru.itmo.clothesadvisor.model.user

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable

@Embeddable
internal data class AppUserHistoryId(
    @field:Column(name = "user_id", nullable = false)
    val userId: Long,
    @field:Column(nullable = false)
    val version: Long,
) : Serializable

@Entity
@Table(name = "app_user_history")
internal class AppUserHistory(user: AppUser, archivedAt: Instant) : Persistable<AppUserHistoryId> {
    @field:EmbeddedId
    private val key = AppUserHistoryId(requireNotNull(user.id), user.version)

    @field:Column(nullable = false, columnDefinition = "text")
    val login: String = user.login

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(nullable = false, columnDefinition = "user_role")
    val role: UserRole = user.role

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(nullable = false, columnDefinition = "user_status")
    val status: UserStatus = user.status

    @field:Column(name = "modified_at", nullable = false)
    val modifiedAt: Instant = user.modifiedAt

    @field:Column(name = "archived_at", nullable = false)
    val archivedAt: Instant = archivedAt

    override fun getId(): AppUserHistoryId = key

    override fun isNew(): Boolean = true
}
