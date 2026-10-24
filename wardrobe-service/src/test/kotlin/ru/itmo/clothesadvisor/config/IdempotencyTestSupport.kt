package ru.itmo.clothesadvisor.config

import org.mockito.Mockito.mock
import org.springframework.transaction.PlatformTransactionManager
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.databind.json.JsonMapper

internal fun idempotencyService(transactions: PlatformTransactionManager) =
    IdempotencyService(mock(IdempotencyCommandRepository::class.java), JsonMapper.builder().findAndAddModules().build(), transactions)
