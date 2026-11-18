package ru.itmo.clothesadvisor.service.ai

import java.time.LocalDate
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.repository.ai.AiJobRepository

internal class JobInputConflict : RuntimeException()

@Service
internal class AiJobValidation(
    private val jobs: AiJobRepository, private val selections: AiSelectionService,
    manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    fun validate(id: UUID, month: LocalDate) {
        val owner = jobs.findOwner(id, month) ?: throw EntityNotFoundException()
        if (!authorized(owner)) throw AccessDeniedException()
        transaction.executeWithoutResult {
            val job = jobs.lock(id) ?: throw EntityNotFoundException()
            if (job.ownerId != owner || job.month != month || !job.active || job.expired || !jobs.currentInput(job)) {
                throw JobInputConflict()
            }
        }
    }

    // Account calls run before any local transaction. Transport failures propagate for retry.
    fun authorized(owner: Long): Boolean = try {
        selections.requireAccess(owner)
        true
    } catch (_: AccessDeniedException) {
        false
    } catch (error: AiSelectionException) {
        // requireAccess reports UNAVAILABLE when this login no longer owns the personal connection.
        if (error.failure != AiSelectionFailure.UNAVAILABLE) throw error
        false
    }
}
