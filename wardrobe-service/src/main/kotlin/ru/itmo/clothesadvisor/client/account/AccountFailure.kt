package ru.itmo.clothesadvisor.client.account

import java.util.function.Predicate
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException

internal fun accountFailure(error: Throwable): RuntimeException {
    val denial = generateSequence(error) { it.cause }.firstOrNull {
        it is EntityNotFoundException || it is AccessDeniedException
    }
    return when (denial) {
        is EntityNotFoundException -> EntityNotFoundException()
        is AccessDeniedException -> AccessDeniedException()
        else -> AccountUnavailableException()
    }
}

internal class AccountDomainFailurePredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is EntityNotFoundException || it is AccessDeniedException
        }
}
