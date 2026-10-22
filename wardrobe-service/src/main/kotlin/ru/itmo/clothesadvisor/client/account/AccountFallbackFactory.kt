package ru.itmo.clothesadvisor.client.account

import java.util.function.Predicate
import org.springframework.cloud.openfeign.FallbackFactory
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException

internal class AccountFallbackFactory : FallbackFactory<AccountClient> {
    override fun create(cause: Throwable): AccountClient {
        val denial = generateSequence(cause) { it.cause }.firstOrNull {
            it is EntityNotFoundException || it is AccessDeniedException
        }
        fun failure(): RuntimeException = when (denial) {
            is EntityNotFoundException -> EntityNotFoundException()
            is AccessDeniedException -> AccessDeniedException()
            else -> AccountUnavailableException()
        }
        return object : AccountClient {
            override fun getUser(userId: Long): AccountUserResponse = throw failure()

            override fun requireStylistAccess(stylistId: Long, ownerId: Long): Unit = throw failure()

            override fun requireAdminAccess(adminId: Long, ownerId: Long): Unit = throw failure()
        }
    }
}

internal class AccountDomainFailurePredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is EntityNotFoundException || it is AccessDeniedException
        }
}
