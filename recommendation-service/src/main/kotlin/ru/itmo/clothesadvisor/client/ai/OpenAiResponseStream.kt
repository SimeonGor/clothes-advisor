package ru.itmo.clothesadvisor.client.ai

import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionFailure

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal class OpenAiResponseStream(mapper: JsonMapper) {
    private val reader =
        mapper
            .reader()
            .with(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            )

    fun read(stream: InputStream): String {
        val input =
            InputStreamReader(
                    stream,
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT),
                )
                .buffered()
        val deltas = StringBuilder()
        var textPart: TextPart? = null

        while (true) {
            val event = readEvent(input)
            val type = event.path("type").asString()
            when {
                type == "error" || type == "response.failed" ->
                    throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
                type == "response.incomplete" -> invalid()
                type.startsWith("response.refusal.") ->
                    throw AiSelectionException(AiSelectionFailure.NO_SELECTION)
                type == "response.output_text.delta" ->
                    textPart = appendDelta(event, textPart, deltas)
                type == "response.completed" -> return completedText(event.path("response"), deltas)
            }
        }
    }

    private fun readEvent(input: Reader): JsonNode {
        var eventName = ""
        val data = StringBuilder()
        while (true) {
            val value = readLine(input)
            if (value.isEmpty()) {
                if (data.isNotEmpty()) {
                    val event = json(data.toString())
                    val type = event.path("type").takeIf { it.isString }?.asString() ?: invalidStream()
                    if (eventName.isNotEmpty() && eventName != type) {
                        invalidStream()
                    }
                    return event
                }
                eventName = ""
            } else if (!value.startsWith(':')) {
                val colon = value.indexOf(':')
                val field = if (colon < 0) value else value.substring(0, colon)
                val fieldValue = if (colon < 0) "" else value.substring(colon + 1).removePrefix(" ")
                when (field) {
                    "event" -> eventName = fieldValue
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        if (fieldValue.length > LIMIT - data.length) {
                            invalidStream()
                        }
                        data.append(fieldValue)
                    }
                }
            }
        }
    }

    private fun readLine(input: Reader): String {
        val line = StringBuilder()
        while (true) {
            val character = input.read()
            if (character == -1) {
                // EOF cannot dispatch an unterminated event.
                invalidStream()
            }
            if (character == '\n'.code) {
                return line.toString().removeSuffix("\r")
            }
            line.append(character.toChar())
            if (line.length > LIMIT) {
                invalidStream()
            }
        }
    }

    private fun appendDelta(
        event: JsonNode,
        expectedPart: TextPart?,
        deltas: StringBuilder,
    ): TextPart {
        val item = event.path("item_id")
        val output = event.path("output_index")
        val content = event.path("content_index")
        if (!item.isString || item.asString().isBlank()) {
            invalidStream()
        }
        if (!output.isIntegralNumber || !output.canConvertToLong() || output.longValue() < 0) {
            invalidStream()
        }
        if (!content.isIntegralNumber || !content.canConvertToLong() || content.longValue() < 0) {
            invalidStream()
        }

        val part = TextPart(item.asString(), output.longValue(), content.longValue())
        if (expectedPart != null && expectedPart != part) {
            invalidStream()
        }
        val delta = event.path("delta")
        if (!delta.isString || delta.asString().length > LIMIT - deltas.length) {
            invalidStream()
        }
        deltas.append(delta.asString())

        return part
    }

    private fun completedText(response: JsonNode, deltas: StringBuilder): String {
        val error = response.path("error")
        val status = response.path("status").asString()
        if ((!error.isNull && !error.isMissingNode) || status == "failed") {
            throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
        }
        if (status != "completed") {
            invalidStream()
        }
        return finalText(response) ?: deltas.toString().takeIf { it.isNotBlank() } ?: invalid()
    }

    private fun finalText(response: JsonNode): String? {
        val output = response.path("output")
        if (!output.isArray) {
            invalid()
        }
        if (output.any { it.path("type").asString() == "refusal" }) {
            throw AiSelectionException(AiSelectionFailure.NO_SELECTION)
        }
        val messages = output.filter { it.path("type").asString() == "message" }
        if (messages.isEmpty() && output.isEmpty) {
            return null
        }
        if (messages.size != 1) {
            invalid()
        }
        val message = messages.single()
        if (
            message.path("status").asString() != "completed" ||
                message.path("role").asString() != "assistant"
        ) {
            invalid()
        }
        val content = message.path("content")
        if (!content.isArray) {
            invalid()
        }
        if (content.any { it.path("type").asString() == "refusal" }) {
            throw AiSelectionException(AiSelectionFailure.NO_SELECTION)
        }
        if (
            content.size() != 1 ||
                content[0].path("type").asString() != "output_text" ||
                !content[0].path("text").isString
        ) {
            invalid()
        }
        return content[0].path("text").asString().takeIf { it.isNotBlank() }
    }

    private fun json(value: String): JsonNode =
        try {
            reader.readTree(value)?.takeIf { it.isObject } ?: invalidStream()
        } catch (_: JacksonException) {
            invalidStream()
        }

    // A broken stream cannot confirm that the provider has stopped processing this request.
    private fun invalidStream(): Nothing = throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE, outcomeUnknown = true)

    private fun invalid(): Nothing = throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)

    private data class TextPart(val itemId: String, val outputIndex: Long, val contentIndex: Long)

    private companion object {
        const val LIMIT = 1_048_576
    }
}
