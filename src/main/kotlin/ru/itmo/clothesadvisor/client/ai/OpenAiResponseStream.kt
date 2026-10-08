package ru.itmo.clothesadvisor.client.ai

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal class OpenAiResponseStream(mapper: JsonMapper) {
    private val reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    fun read(stream: InputStream): String {
        val input = InputStreamReader(stream, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()
        var eventName = ""
        val data = StringBuilder()
        val deltas = StringBuilder()
        var group: Triple<String, Long, Long>? = null

        fun dispatch(): String? {
            if (data.isEmpty()) return null
            val event = json(data.toString())
            val type = event.path("type").takeIf { it.isString }?.asString() ?: invalid()
            if (eventName.isNotEmpty() && eventName != type) invalid()

            when {
                type == "error" || type == "response.failed" -> throw AiOutfitException(503)
                type == "response.incomplete" -> invalid()
                type.startsWith("response.refusal.") -> throw AiOutfitException(422)
                type == "response.output_text.delta" -> {
                    val item = event.path("item_id")
                    val output = event.path("output_index")
                    val content = event.path("content_index")

                    if (!item.isString || item.asString().isBlank() ||
                        !output.isIntegralNumber || !output.canConvertToLong() || output.longValue() < 0 ||
                        !content.isIntegralNumber || !content.canConvertToLong() || content.longValue() < 0) invalid()

                    val next = Triple(item.asString(), output.longValue(), content.longValue())
                    if (group != null && group != next) invalid()
                    group = next
                    val delta = event.path("delta")
                    if (!delta.isString || delta.asString().length > LIMIT - deltas.length) invalid()
                    deltas.append(delta.asString())
                }
                type == "response.completed" -> {
                    val response = event.path("response")
                    if (!response.path("error").isNull && !response.path("error").isMissingNode ||
                        response.path("status").asString() == "failed") throw AiOutfitException(503)
                    if (response.path("status").asString() != "completed") invalid()
                    return finalText(response) ?: deltas.toString().takeIf { it.isNotBlank() } ?: invalid()
                }
            }
            return null
        }

        while (true) {
            val line = StringBuilder()
            while (true) {
                val character = input.read()
                if (character == -1) invalid() // EOF cannot dispatch an unterminated event.
                if (character == '\n'.code) break
                line.append(character.toChar())
                if (line.length > LIMIT) invalid()
            }

            val value = line.toString().removeSuffix("\r")

            if (value.isEmpty()) {
                dispatch()?.let { return it }
                data.setLength(0)
                eventName = ""
            } else if (!value.startsWith(':')) {
                val colon = value.indexOf(':')
                val field = if (colon < 0) value else value.substring(0, colon)
                val fieldValue = if (colon < 0) "" else value.substring(colon + 1).removePrefix(" ")
                when (field) {
                    "event" -> eventName = fieldValue
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        if (fieldValue.length > LIMIT - data.length) invalid()
                        data.append(fieldValue)
                    }
                }
            }
        }
    }

    private fun finalText(response: JsonNode): String? {
        val output = response.path("output")
        if (!output.isArray) invalid()
        if (output.any { it.path("type").asString() == "refusal" }) throw AiOutfitException(422)
        val messages = output.filter { it.path("type").asString() == "message" }
        if (messages.isEmpty() && output.isEmpty) return null
        if (messages.size != 1) invalid()
        val message = messages.single()
        if (message.path("status").asString() != "completed" || message.path("role").asString() != "assistant") invalid()
        val content = message.path("content")
        if (!content.isArray) invalid()
        if (content.any { it.path("type").asString() == "refusal" }) throw AiOutfitException(422)
        if (content.size() != 1 || content[0].path("type").asString() != "output_text" ||
            !content[0].path("text").isString) invalid()
        return content[0].path("text").asString().takeIf { it.isNotBlank() }
    }

    private fun json(value: String): JsonNode = try {
        reader.readTree(value)?.takeIf { it.isObject } ?: invalid()
    } catch (_: JacksonException) { invalid() }

    private fun invalid(): Nothing = throw AiOutfitException(502)

    private companion object { const val LIMIT = 1_048_576 }
}
