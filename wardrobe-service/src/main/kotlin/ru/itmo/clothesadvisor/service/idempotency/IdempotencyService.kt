package ru.itmo.clothesadvisor.service.idempotency

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

internal class IdempotencyException(val status: HttpStatus, val code: String) : RuntimeException(code)

@Service
internal class IdempotencyService(
    private val commands: IdempotencyCommandRepository,
    private val mapper: JsonMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun <T : Any> execute(
        actorId: Long,
        operation: IdempotencyOperation,
        key: String?,
        payload: Any,
        responseType: TypeReference<T>,
        files: List<ByteArray> = emptyList(),
        responseStatus: Int = HttpStatus.CREATED.value(),
        action: (Completion) -> T,
    ): T {
        if (key == null) return action(Completion(null, responseStatus))
        if (key.isEmpty() || key.length > 255 || key.any { it.code !in 32..126 }) {
            throw IdempotencyException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key")
        }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Idempotent execution must start outside a transaction"
        }
        val fingerprint = fingerprint(payload, files)
        val claimed = transaction.execute { commands.claim(actorId, operation.name, key, fingerprint) }
        if (claimed == null) {
            val existing = commands.findCommand(actorId, operation.name, key)
            if (existing.fingerprint != fingerprint) conflict("idempotency_key_reused")
            when (existing.state) {
                "SUCCEEDED" -> {
                    if (existing.responseStatus != responseStatus) conflict("idempotency_api_version_conflict")
                    return mapper.readValue(requireNotNull(existing.responseJson), responseType)
                }
                "FAILED" -> conflict("idempotency_failed")
                else -> conflict("idempotency_in_progress")
            }
        }

        val completion = Completion(claimed.id, responseStatus)
        try {
            val result = action(completion)
            check(completion.completed) { "Idempotent action did not commit a result" }
            return result
        } catch (failure: Throwable) {
            try {
                transaction.executeWithoutResult { commands.fail(claimed.id) }
            } catch (recordingFailure: Throwable) {
                failure.addSuppressed(recordingFailure)
            }
            throw failure
        }
    }

    private fun fingerprint(payload: Any, files: List<ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        (listOf(mapper.writeValueAsBytes(payload)) + files).forEach { bytes ->
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun conflict(code: String): Nothing = throw IdempotencyException(HttpStatus.CONFLICT, code)

    inner class Completion internal constructor(private val commandId: Long?, private val responseStatus: Int) {
        internal var completed = false
            private set

        fun <T : Any> commit(save: () -> T): T {
            check(!completed) { "Result already committed" }
            if (commandId == null) return save()
            val result = transaction.execute {
                val saved = save()
                check(commands.succeed(commandId, mapper.writeValueAsString(saved), responseStatus) == 1)
                saved
            }
            completed = true
            return requireNotNull(result)
        }
    }
}
