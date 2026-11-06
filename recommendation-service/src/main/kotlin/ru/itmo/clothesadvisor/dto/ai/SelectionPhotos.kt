package ru.itmo.clothesadvisor.dto.ai

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionFailure

/** Owned by the synchronous selection operation, including its request-body writer. */
class SelectionPhotos : AutoCloseable {
    private val images = mutableListOf<SelectionImage>()

    fun add(bytes: ByteArray): SelectionImage {
        require(images.size < 20 && bytes.size in 1..10_000_000)
        val path = Files.createTempFile("selection-photo-", ".base64",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.newOutputStream(path).use { output ->
                Base64.getEncoder().wrap(output).use { it.write(bytes) }
            }
            return SelectionImage(path).also { images += it }
        } catch (error: Throwable) {
            Files.deleteIfExists(path)
            throw error
        }
    }

    override fun close() {
        var failure: Exception? = null
        images.forEach {
            try { it.close() } catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
    }
}

class SelectionImageDeserializer : tools.jackson.databind.ValueDeserializer<SelectionImage>() {
    override fun deserialize(
        parser: tools.jackson.core.JsonParser,
        context: tools.jackson.databind.DeserializationContext,
    ): SelectionImage {
        if (!parser.hasToken(tools.jackson.core.JsonToken.VALUE_STRING)) invalidImage()
        val text = parser.string
        if (text.isEmpty() || text.length > 13_333_336 || text.length % 4 != 0) invalidImage()
        val bytes = try { Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) { invalidImage() }
        if (bytes.size !in 1..10_000_000 || Base64.getEncoder().encodeToString(bytes) != text) invalidImage()
        val photos = context.getAttribute(SelectionPhotos::class.java) as SelectionPhotos
        return try { photos.add(bytes) } catch (_: java.io.IOException) {
            throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
        } catch (_: IllegalArgumentException) { invalidImage() }
    }

    private fun invalidImage(): Nothing = throw AiSelectionException(AiSelectionFailure.INVALID_REQUEST)
}

class SelectionImage internal constructor(internal val path: Path) : AutoCloseable {
    private var readers = 0
    private var closed = false

    @Synchronized
    fun open(): InputStream {
        check(!closed) { "Selection image is closed" }
        val input = Files.newInputStream(path)
        readers++
        return object : FilterInputStream(input) {
            private var released = false
            override fun close() {
                synchronized(this@SelectionImage) {
                    if (released) return
                    released = true
                    try { super.close() } finally {
                        readers--
                        if (closed && readers == 0) Files.deleteIfExists(path)
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        if (readers == 0) Files.deleteIfExists(path)
    }

    fun writeJson(generator: JsonGenerator, prefix: String = "") {
        // Base64 and the fixed data-URL prefix contain no JSON escape characters.
        generator.writeRawValue("\"" + prefix)
        open().bufferedReader(Charsets.US_ASCII).use { input ->
            val chars = CharArray(8192)
            while (true) {
                val count = input.read(chars)
                if (count < 0) break
                generator.writeRaw(chars, 0, count)
            }
        }
        generator.writeRaw("\"")
    }
}

class SelectionImageSerializer : ValueSerializer<SelectionImage>() {
    override fun serialize(value: SelectionImage, generator: JsonGenerator, context: SerializationContext) {
        value.writeJson(generator)
    }
}
