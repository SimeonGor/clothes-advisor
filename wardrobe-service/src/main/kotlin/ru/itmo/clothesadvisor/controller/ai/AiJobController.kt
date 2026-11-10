package ru.itmo.clothesadvisor.controller.ai

import java.net.URI
import java.util.UUID
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.JobAccepted
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.repository.ai.AiQueueFullException
import ru.itmo.clothesadvisor.service.ai.AiJobService
import ru.itmo.clothesadvisor.service.blockingCall

@RestController
internal class AiJobController(private val jobs: AiJobService) : AiJobApi {
    override fun outfit(
        user: CurrentUser, request: CreateAiOutfitRequest,
        key: String?,
    ) = accepted { jobs.outfit(user.id, request, key) }

    override fun replacement(
        user: CurrentUser, id: Long,
        request: CreateAiOutfitReplacementRequest,
        key: String?,
    ) = accepted { jobs.replacement(user.id, id, request, key) }

    override fun capsule(
        user: CurrentUser, request: CreateAiCapsuleRequest,
        key: String?,
    ) = accepted { jobs.capsule(user.id, request, key) }

    override fun get(user: CurrentUser, jobId: UUID) =
        blockingCall { jobs.get(user, jobId) }

    override fun list(
        user: CurrentUser,
        page: Int,
        size: Int,
    ) = blockingCall { jobs.list(user, page, size) }.map { (jobs, count) ->
        ResponseEntity.ok().header("X-Total-Count", count.toString()).body(jobs)
    }

    private fun accepted(action: () -> JobAccepted) = blockingCall(action).map {
        ResponseEntity.accepted().location(URI("/api/v2/me/ai-jobs/${it.jobId}")).body(it)
    }

    @ExceptionHandler(AiSelectionException::class)
    fun selection(error: AiSelectionException) = when (error.failure) {
        AiSelectionFailure.INVALID_REQUEST -> failure(400, "invalid_request")
        AiSelectionFailure.SOURCE_CHANGED -> failure(409, "source_changed")
        else -> unavailable()
    }

    @ExceptionHandler(EntityNotFoundException::class)
    fun notFound() = failure(404, "not_found")

    @ExceptionHandler(AiQueueFullException::class)
    fun full() = failure(503, "ai_queue_full")

    @ExceptionHandler(AccountUnavailableException::class, DataAccessException::class, TransactionException::class)
    fun unavailable() = failure(503, "ai_unavailable")

    private fun failure(status: Int, code: String) = ResponseEntity.status(status).body(ApiErrorResponse(code))
}
