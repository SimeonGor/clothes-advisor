package ru.itmo.clothesadvisor.controller.ai

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.net.URI
import java.util.UUID
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.*
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
@RequestMapping("/api/v2")
internal class AiJobController(private val jobs: AiJobService) {
    @PostMapping("/outfits/ai")
    @Operation(summary = "Принять задание на подбор образа")
    @ApiResponse(responseCode = "202", description = "Задание принято", useReturnTypeSchema = true,
        headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))])
    fun outfit(
        @Parameter(hidden = true) user: CurrentUser, @Valid @RequestBody request: CreateAiOutfitRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) = accepted { jobs.outfit(user.id, request, key) }

    @PostMapping("/outfits/{id}/ai-replacements")
    @Operation(summary = "Принять задание на замену вещей в образе")
    @ApiResponse(responseCode = "202", description = "Задание принято", useReturnTypeSchema = true,
        headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))])
    fun replacement(
        @Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long,
        @Valid @RequestBody request: CreateAiOutfitReplacementRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) = accepted { jobs.replacement(user.id, id, request, key) }

    @PostMapping("/me/capsules/ai")
    @Operation(summary = "Принять задание на подбор капсулы")
    @ApiResponse(responseCode = "202", description = "Задание принято", useReturnTypeSchema = true,
        headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))])
    fun capsule(
        @Parameter(hidden = true) user: CurrentUser, @Valid @RequestBody request: CreateAiCapsuleRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) = accepted { jobs.capsule(user.id, request, key) }

    @GetMapping("/me/ai-jobs/{jobId}")
    @Operation(summary = "Прочитать своё задание")
    fun get(@Parameter(hidden = true) user: CurrentUser, @PathVariable jobId: UUID) =
        blockingCall { jobs.get(user, jobId) }

    @GetMapping("/me/ai-jobs")
    @Operation(summary = "Прочитать список своих заданий")
    @ApiResponse(responseCode = "200", description = "Список заданий", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", schema = Schema(type = "integer", format = "int64"))])
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
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
