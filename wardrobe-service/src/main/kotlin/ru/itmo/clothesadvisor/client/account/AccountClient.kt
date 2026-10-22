package ru.itmo.clothesadvisor.client.account

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse

@FeignClient(
    name = "account-service",
    configuration = [AccountClientConfiguration::class],
    fallbackFactory = AccountFallbackFactory::class,
)
internal interface AccountClient {
    @GetMapping("/internal/users/{userId}")
    fun getUser(@PathVariable("userId") userId: Long): AccountUserResponse

    @GetMapping("/internal/stylist-access/{stylistId}/clients/{ownerId}")
    fun requireStylistAccess(
        @PathVariable("stylistId") stylistId: Long,
        @PathVariable("ownerId") ownerId: Long,
    )

    @GetMapping("/internal/admin-access/{adminId}/users/{ownerId}")
    fun requireAdminAccess(
        @PathVariable("adminId") adminId: Long,
        @PathVariable("ownerId") ownerId: Long,
    )
}
