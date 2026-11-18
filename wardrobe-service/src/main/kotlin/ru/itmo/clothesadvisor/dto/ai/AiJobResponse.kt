package ru.itmo.clothesadvisor.dto.ai

import java.time.Instant
import java.util.UUID

internal enum class AiJobType { OUTFIT_SELECTION, OUTFIT_REPLACEMENT, CAPSULE_SELECTION }
internal enum class AiJobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, UNKNOWN }

internal data class JobAccepted(val jobId: UUID, val status: AiJobStatus = AiJobStatus.QUEUED)

internal data class AiJobResponse(
    val jobId: UUID,
    val type: AiJobType,
    val status: AiJobStatus,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val result: AiJobResult?,
    val error: AiJobError?,
)

internal data class AiJobResult(val type: String, val id: Long, val url: String)
internal data class AiJobError(val code: String, val message: String)
