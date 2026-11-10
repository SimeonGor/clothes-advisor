package ru.itmo.clothesadvisor.service.ai

import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.dto.ai.*
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.ai.AiJobRepository
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import tools.jackson.core.type.TypeReference

@Service
internal class AiJobService(
    private val jobs: AiJobRepository,
    private val outfits: OutfitService,
    private val selections: AiSelectionService,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${app.ai.queue-capacity:5}") private val capacity: Int,
) {
    private val read = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }
    private val write = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun outfit(ownerId: Long, request: CreateAiOutfitRequest, key: String?) =
        accept(ownerId, key, AiJobType.OUTFIT_SELECTION, request, request.name) { request.criteria() }

    fun replacement(ownerId: Long, sourceId: Long, request: CreateAiOutfitReplacementRequest, key: String?) =
        accept(ownerId, key, AiJobType.OUTFIT_REPLACEMENT, request, request.name, sourceId) {
            request.criteria(outfits.get(ownerId, sourceId))
        }

    fun capsule(ownerId: Long, request: CreateAiCapsuleRequest, key: String?) =
        accept(ownerId, key, AiJobType.CAPSULE_SELECTION, request, request.name) { request.criteria() }

    private fun accept(
        ownerId: Long, key: String?, type: AiJobType, request: Any, name: String,
        sourceId: Long? = null, prepare: () -> AiSelectionCriteria,
    ): JobAccepted {
        selections.requireAccess(ownerId)
        val operation = when (type) {
            AiJobType.OUTFIT_SELECTION -> IdempotencyOperation.CREATE_AI_OUTFIT
            AiJobType.OUTFIT_REPLACEMENT -> IdempotencyOperation.CREATE_AI_OUTFIT_REPLACEMENT
            AiJobType.CAPSULE_SELECTION -> IdempotencyOperation.CREATE_AI_CAPSULE
        }
        val payload = if (sourceId == null) listOf(ownerId, request) else listOf(ownerId, sourceId, request)
        return idempotency.execute(
            ownerId, operation, key, payload, object : TypeReference<JobAccepted>() {},
            responseStatus = HttpStatus.ACCEPTED.value(),
        ) { completion ->
            val input = read.execute {
                val criteria = prepare()
                AiJobInput(name, sourceId, criteria.weather.precipitationTypeId, selections.snapshot(ownerId, criteria))
            }
            write.execute { completion.commit { jobs.accept(ownerId, type, input, capacity) } }
        }
    }

    fun get(user: CurrentUser, jobId: UUID): AiJobResponse {
        requireUser(user)
        return jobs.get(user.id, jobId)
    }

    fun list(user: CurrentUser, page: Int, size: Int): Pair<List<AiJobResponse>, Long> {
        requireUser(user)
        return read.execute { jobs.list(user.id, page, size) to jobs.count(user.id) }
    }

    private fun requireUser(user: CurrentUser) {
        if (user.role != UserRole.USER) throw AccessDeniedException()
    }
}
