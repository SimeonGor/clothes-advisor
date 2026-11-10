package ru.itmo.clothesadvisor.recommendationservice.execution

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionCandidate
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.SelectionPhotos
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionFailure

@Component
@ConditionalOnProperty("app.ai-worker.enabled", havingValue = "true")
internal class ExecutionWorker(
    private val store: ExecutionStore,
    private val inputs: ExecutionInputs,
    private val client: OpenAiSelectionClient,
) {
    // External calls can occupy this thread for a minute. Outbox and config polling run independently.
    private val polling = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ai-worker").factory())
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun start() {
        polling.scheduleWithFixedDelay({
            try {
                tick()
            } catch (_: Exception) {
                log.warn("AI worker iteration interrupted; inspect the durable execution slot")
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun stop() { polling.shutdownNow() }

    fun tick() {
        store.expireWaiting()
        val job = store.claimAsync() ?: return
        val photos = SelectionPhotos()
        try {
            val candidates = job.input.candidates.map { candidate ->
                val image = try {
                    photos.add(prepare(job) { inputs.photo(candidate) })
                } catch (_: IOException) {
                    throw PreparationFailure("dependency_unavailable")
                }
                ItemSelectionCandidate(candidate.itemId, candidate.name, candidate.categoryCode, candidate.categoryName,
                    candidate.color, candidate.material, candidate.contentType, image)
            }
            val request = ItemSelectionRequest(job.input.weather, candidates, job.input.occasion, job.input.preferredStyle,
                job.input.requiredItemIds, job.input.stylePreferences)
            prepare(job) { inputs.validate(job) }
            var started = false
            val response = try {
                prepare(job) {
                    try {
                        val instructions = client.instructions(job.goal)
                        client.select(job.goal, request, instructions) {
                            if (!store.startAsync(job, instructions)) throw PreparationFailure("queue_expired")
                            started = true
                        }
                    } catch (error: AiSelectionException) {
                        if (!started && error.failure == AiSelectionFailure.UNAVAILABLE) throw TemporaryDependencyFailure()
                        throw error
                    }
                }
            } catch (error: AiSelectionException) {
                when {
                    error.outcomeUnknown -> store.finish(job.execution, "UNKNOWN", "ai_outcome_unknown")
                    error.failure == AiSelectionFailure.INVALID_RESPONSE -> store.finish(job.execution, "FAILED", "invalid_ai_response")
                    else -> store.finish(job.execution, "FAILED", "selection_unavailable")
                }
                return
            }
            store.finish(job.execution, "SUCCEEDED", response = response)
        } catch (failure: PreparationFailure) {
            store.finish(job.execution, "FAILED", failure.code)
        } finally {
            try { photos.close() } catch (_: IOException) { log.warn("AI worker could not remove a staged photo") }
        }
    }

    private fun <T> prepare(job: ExecutionJob, action: () -> T): T {
        repeat(3) { attempt ->
            if (!store.stillPreparing(job)) throw PreparationFailure("queue_expired")
            try {
                return action()
            } catch (_: TemporaryDependencyFailure) {
                if (attempt < 2) Thread.sleep(1000L shl attempt)
            }
        }
        throw PreparationFailure("dependency_unavailable")
    }
}
