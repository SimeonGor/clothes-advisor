package ru.itmo.clothesadvisor.client.account

import feign.Param
import feign.RequestLine
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse

internal interface AccountClient {
    @RequestLine("GET /internal/users/{userId}")
    fun getUser(@Param("userId") userId: Long): Mono<AccountUserResponse>

    @RequestLine("GET /internal/stylist-access/{stylistId}/clients/{ownerId}")
    fun requireStylistAccess(
        @Param("stylistId") stylistId: Long,
        @Param("ownerId") ownerId: Long,
    ): Mono<Void>

    @RequestLine("GET /internal/admin-access/{adminId}/users/{ownerId}")
    fun requireAdminAccess(
        @Param("adminId") adminId: Long,
        @Param("ownerId") ownerId: Long,
    ): Mono<Void>
}
