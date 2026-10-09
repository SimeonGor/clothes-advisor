package ru.itmo.clothesadvisor.model.access

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal data class AccessGrantId(
    val ownerId: Long,
    val stylistId: Long,
)

@Table("access_grant")
internal class AccessGrant(@Id @Embedded.Empty val id: AccessGrantId)
