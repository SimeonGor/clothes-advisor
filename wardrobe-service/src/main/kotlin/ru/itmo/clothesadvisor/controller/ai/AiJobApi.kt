package ru.itmo.clothesadvisor.controller.ai

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.ai.AiJobResponse
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.JobAccepted
import ru.itmo.clothesadvisor.dto.user.CurrentUser

@RequestMapping("/api/v2")
internal interface AiJobApi {
    @PostMapping("/outfits/ai")
    @Operation(summary = "Принять задание на подбор образа")
    @ApiResponse(responseCode = "202", description = "Задание принято", useReturnTypeSchema = true,
        headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))])
    fun outfit(
        @Parameter(hidden = true) user: CurrentUser, @Valid @RequestBody request: CreateAiOutfitRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): Mono<ResponseEntity<JobAccepted>>

    @PostMapping("/outfits/{id}/ai-replacements")
    @Operation(summary = "Принять задание на замену вещей в образе")
    @ApiResponse(responseCode = "202", description = "Задание принято", useReturnTypeSchema = true,
        headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))])
    fun replacement(
        @Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long,
        @Valid @RequestBody request: CreateAiOutfitReplacementRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): Mono<ResponseEntity<JobAccepted>>

    @PostMapping("/me/capsules/ai")
    @Operation(summary = "Принять задание на подбор капсулы")
    @ApiResponse(responseCode = "202", description = "Задание принято", useReturnTypeSchema = true,
        headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))])
    fun capsule(
        @Parameter(hidden = true) user: CurrentUser, @Valid @RequestBody request: CreateAiCapsuleRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): Mono<ResponseEntity<JobAccepted>>

    @GetMapping("/me/ai-jobs/{jobId}")
    @Operation(summary = "Прочитать своё задание")
    fun get(@Parameter(hidden = true) user: CurrentUser, @PathVariable jobId: UUID): Mono<AiJobResponse>

    @GetMapping("/me/ai-jobs")
    @Operation(summary = "Прочитать список своих заданий")
    @ApiResponse(responseCode = "200", description = "Список заданий", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", schema = Schema(type = "integer", format = "int64"))])
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): Mono<ResponseEntity<List<AiJobResponse>>>
}
