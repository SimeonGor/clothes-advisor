package ru.itmo.clothesadvisor.client.account

import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException

// Synchronous JDBC workflows invoke this boundary on their existing boundedElastic workers.
@Service
internal class BlockingAccountClient(private val client: AccountClient) {
    fun getUser(userId: Long): AccountUserResponse =
        client.getUser(userId).block() ?: throw AccountUnavailableException()

    fun requireStylistAccess(stylistId: Long, ownerId: Long) {
        client.requireStylistAccess(stylistId, ownerId).block()
    }

    fun requireAdminAccess(adminId: Long, ownerId: Long) {
        client.requireAdminAccess(adminId, ownerId).block()
    }
}
