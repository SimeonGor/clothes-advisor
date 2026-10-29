package ru.itmo.clothesadvisor.client.ai

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.CharacterCodingException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import ru.itmo.clothesadvisor.config.SelectionPromptSettings
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.SelectionImage
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.adapter.JdkFlowAdapter
import reactor.core.publisher.Flux
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

enum class AiSelectionFailure(val status: Int) {
    INVALID_REQUEST(400),
    NO_SELECTION(422),
    NO_OUTFIT(422),
    NO_CAPSULE(422),
    INVALID_RESPONSE(502),
    UNAVAILABLE(503),
    TIMEOUT(504),
}

class AiSelectionException(val failure: AiSelectionFailure) : RuntimeException() {
    val status: Int
        get() = failure.status
}

enum class SelectionGoal { OUTFIT, CAPSULE }

class OpenAiSelectionClient internal constructor(
    private val http: HttpClient?,
    private val mapper: JsonMapper,
    private val credentials: ChatGptCredentials,
    private val model: String,
    private val endpoint: URI,
    private val timeout: Duration,
    private val outfitPromptSettings: SelectionPromptSettings,
    private val capsulePromptSettings: SelectionPromptSettings,
) : AutoCloseable {
    fun select(goal: SelectionGoal, selection: ItemSelectionRequest): ItemSelectionResponse {
        val promptSettings = when (goal) {
            SelectionGoal.OUTFIT -> outfitPromptSettings
            SelectionGoal.CAPSULE -> capsulePromptSettings
        }
        val prompt = promptSettings.snapshot() ?: throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
        if (http == null || model.isBlank()) {
            throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
        }
        val accessToken = credentials.accessToken()
        val allowed = selection.candidates.map { it.itemId }.toSet()
        val body = requestBody(selection, allowed, prompt.text, goal)

        val request =
            try {
                HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.fromPublisher(JdkFlowAdapter.publisherToFlowPublisher(
                        Flux.from(DataBufferUtils.outputStreamPublisher(
                            { output -> mapper.writeValue(output, body) },
                            DefaultDataBufferFactory.sharedInstance,
                            { task -> Thread.startVirtualThread(task) },
                            8192,
                        )).map { buffer ->
                            try {
                                ByteBuffer.wrap(ByteArray(buffer.readableByteCount()).also { buffer.read(it) })
                            } finally { DataBufferUtils.release(buffer) }
                        },
                    )))
                    .build()
            } catch (_: IllegalArgumentException) {
                throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
            }

        val openBody = AtomicReference<InputStream?>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        val pending =
            executor.submit(
                Callable {
                    val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    response.body().use { stream ->
                        openBody.set(stream)
                        if (Thread.currentThread().isInterrupted) {
                            throw AiSelectionException(AiSelectionFailure.TIMEOUT)
                        }
                        if (response.statusCode() !in 200..299) {
                            throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
                        }
                        val contentType = response.headers().firstValue("Content-Type").orElse(null)
                        if (
                            contentType != null &&
                                !contentType
                                    .substringBefore(';')
                                    .trim()
                                    .equals("text/event-stream", true)
                        ) {
                            throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
                        }
                        parse(OpenAiResponseStream(mapper).read(stream), allowed, selection.requiredItemIds)
                    }
                },
            )

        return try {
            pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw AiSelectionException(AiSelectionFailure.TIMEOUT)
        } catch (error: ExecutionException) {
            when (val cause = error.cause) {
                is AiSelectionException -> throw cause
                is HttpTimeoutException -> throw AiSelectionException(AiSelectionFailure.TIMEOUT)
                is CharacterCodingException ->
                    throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
                else -> throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
        } finally {
            pending.cancel(true)
            // Closing a network stream must not hold up the caller after its deadline.
            openBody.get()?.let { stream ->
                Thread.startVirtualThread { runCatching { stream.close() } }
            }
            executor.shutdownNow()
        }
    }

    override fun close() {
        http?.close()
    }

    private fun requestBody(selection: ItemSelectionRequest, allowed: Set<Long>, prompt: String, goal: SelectionGoal): Map<String, Any> =
        mapOf(
            "model" to model,
            "store" to false,
            "stream" to true,
            "instructions" to prompt,
            "input" to listOf(mapOf("role" to "user", "content" to inputContent(selection))),
            "text" to mapOf("format" to outputFormat(allowed, goal)),
        )

    private fun inputContent(selection: ItemSelectionRequest): List<Map<String, Any>> {
        val content = mutableListOf<Map<String, Any>>(
            mapOf(
                "type" to "input_text",
                "text" to mapper.writeValueAsString(
                    mapOf(
                        "weather" to selection.weather,
                        "occasion" to selection.occasion,
                        "preferredStyle" to selection.preferredStyle,
                    ) + (selection.requiredItemIds?.let { mapOf("requiredItemIds" to it) } ?: emptyMap()) +
                        (selection.stylePreferences?.let { mapOf("stylePreferences" to it) } ?: emptyMap()),
                ),
            ),
        )
        selection.candidates.forEach { candidate ->
            val description = mapOf(
                "id" to candidate.itemId,
                "categoryCode" to candidate.categoryCode,
                "categoryName" to candidate.categoryName,
                "name" to candidate.name,
                "color" to candidate.color,
                "material" to candidate.material,
            )
            content += mapOf("type" to "input_text", "text" to mapper.writeValueAsString(description))
            content += mapOf(
                "type" to "input_image",
                "image_url" to ImageUrl(candidate.contentType, candidate.imageBase64),
            )
        }
        return content
    }

    private fun outputFormat(allowed: Set<Long>, goal: SelectionGoal): Map<String, Any> {
        val itemIds = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "integer", "enum" to allowed.toList()),
        )
        val schema = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("itemIds", "explanation"),
            "properties" to mapOf("itemIds" to itemIds, "explanation" to mapOf("type" to "string")),
        )
        return mapOf(
            "type" to "json_schema",
            "name" to goal.name.lowercase(),
            "strict" to true,
            "schema" to schema,
        )
    }

    private fun parse(body: String, allowed: Set<Long>, requiredIds: List<Long?>?): ItemSelectionResponse {
        try {
            val reader =
                mapper
                    .reader()
                    .with(
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    )

            val result =
                reader.readTree(body) ?: throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
            val ids = result.path("itemIds")
            val explanation = result.path("explanation")

            if (!result.isObject || result.size() != 2 || !ids.isArray || !explanation.isString ||
                explanation.asString().isBlank() || explanation.asString().length > 2000
            ) {
                throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
            }

            val selected =
                ids.toList().map {
                    if (
                        !it.isIntegralNumber || !it.canConvertToLong() || it.longValue() !in allowed
                    ) {
                        throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
                    }
                    it.longValue()
                }
            if (selected.distinct().size != selected.size) {
                throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
            }
            if (selected.isEmpty()) {
                throw AiSelectionException(AiSelectionFailure.NO_SELECTION)
            }
            if (requiredIds != null && (!selected.containsAll(requiredIds) || selected.none { it !in requiredIds })) {
                throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
            }

            return ItemSelectionResponse(selected, explanation.asString())
        } catch (_: JacksonException) {
            throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
        }
    }
}

@tools.jackson.databind.annotation.JsonSerialize(using = ImageUrlSerializer::class)
private data class ImageUrl(val contentType: String, val image: SelectionImage)

private class ImageUrlSerializer : tools.jackson.databind.ValueSerializer<ImageUrl>() {
    override fun serialize(
        value: ImageUrl,
        generator: tools.jackson.core.JsonGenerator,
        context: tools.jackson.databind.SerializationContext,
    ) {
        value.image.writeJson(generator, "data:${value.contentType};base64,")
    }
}
