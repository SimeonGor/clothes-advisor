package ru.itmo.clothesadvisor.model.capsule

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("capsule")
internal data class Capsule(
    @Id val id: Long,
    val ownerId: Long,
    val name: String,
    val createdAt: Instant,
)
