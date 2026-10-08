package ru.itmo.clothesadvisor.model.access

internal data class AccessGrantId(
    val ownerId: Long,
    val stylistId: Long,
)

internal class AccessGrant(val id: AccessGrantId)
