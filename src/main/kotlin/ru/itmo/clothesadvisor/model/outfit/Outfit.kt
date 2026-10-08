package ru.itmo.clothesadvisor.model.outfit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

internal enum class OutfitSource { USER, STYLIST, AI }

@Entity
@Table(name = "outfit")
internal class Outfit(
    @field:Column(name = "owner_id", nullable = false)
    val ownerId: Long,
    @field:Column(name = "author_id", nullable = false)
    val authorId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(nullable = false, columnDefinition = "outfit_source")
    val source: OutfitSource,
    @field:Column(nullable = false, columnDefinition = "text")
    val name: String,
    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
