package ru.itmo.clothesadvisor.model.access

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

@Embeddable
internal data class AccessGrantId(
    @field:Column(name = "owner_id", nullable = false)
    val ownerId: Long,
    @field:Column(name = "stylist_id", nullable = false)
    val stylistId: Long,
) : Serializable

@Entity
@Table(name = "access_grant")
internal class AccessGrant(@field:EmbeddedId val id: AccessGrantId)
