package ru.itmo.clothesadvisor.client.ai

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
                    throw AiOutfitException(AiOutfitFailure.UNAVAILABLE)
                type == "response.incomplete" -> invalid()
                type.startsWith("response.refusal.") ->
                    throw AiOutfitException(AiOutfitFailure.NO_OUTFIT)
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
                    val type = event.path("type").takeIf { it.isString }?.asString() ?: invalid()
                    if (eventName.isNotEmpty() && eventName != type) {
                        invalid()
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
                            invalid()
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
                invalid()
            }
            if (character == '\n'.code) {
                return line.toString().removeSuffix("\r")
            }
            line.append(character.toChar())
            if (line.length > LIMIT) {
                invalid()
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
            invalid()
        }
        if (!output.isIntegralNumber || !output.canConvertToLong() || output.longValue() < 0) {
            invalid()
        }
        if (!content.isIntegralNumber || !content.canConvertToLong() || content.longValue() < 0) {
            invalid()
        }

        val part = TextPart(item.asString(), output.longValue(), content.longValue())
        if (expectedPart != null && expectedPart != part) {
            invalid()
        }
        val delta = event.path("delta")
        if (!delta.isString || delta.asString().length > LIMIT - deltas.length) {
            invalid()
        }
        deltas.append(delta.asString())

        return part
    }

    private fun completedText(response: JsonNode, deltas: StringBuilder): String {
        val error = response.path("error")
        val status = response.path("status").asString()
        if ((!error.isNull && !error.isMissingNode) || status == "failed") {
            throw AiOutfitException(AiOutfitFailure.UNAVAILABLE)
        }
        if (status != "completed") {
            invalid()
        }
        return finalText(response) ?: deltas.toString().takeIf { it.isNotBlank() } ?: invalid()
    }

    private fun finalText(response: JsonNode): String? {
        val output = response.path("output")
        if (!output.isArray) {
            invalid()
        }
        if (output.any { it.path("type").asString() == "refusal" }) {
            throw AiOutfitException(AiOutfitFailure.NO_OUTFIT)
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
            throw AiOutfitException(AiOutfitFailure.NO_OUTFIT)
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
            reader.readTree(value)?.takeIf { it.isObject } ?: invalid()
        } catch (_: JacksonException) {
            invalid()
        }

    private fun invalid(): Nothing = throw AiOutfitException(AiOutfitFailure.INVALID_RESPONSE)

    private data class TextPart(val itemId: String, val outputIndex: Long, val contentIndex: Long)

    private companion object {
        const val LIMIT = 1_048_576
    }
}
