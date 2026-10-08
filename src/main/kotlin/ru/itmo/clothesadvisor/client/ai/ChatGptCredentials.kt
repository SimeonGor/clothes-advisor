package ru.itmo.clothesadvisor.client.ai

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// The login utility owns these records. Never retain, print, or rewrite the credentials.
internal class ChatGptCredentials(private val mapper: JsonMapper, private val clock: Clock, private val directory: String) {
    fun accessToken(): String = try {
        if (directory.isBlank()) unavailable()
        val root = Path.of(directory).toAbsolutePath()
        var ancestor = root.root
        for (part in root) {
            ancestor = ancestor.resolve(part)
            if (Files.isSymbolicLink(ancestor)) unavailable()
        }

        fun attributes(path: Path, isDirectory: Boolean): BasicFileAttributes {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink ||
                (if (isDirectory) !attributes.isDirectory else !attributes.isRegularFile)
            ) unavailable()
            return attributes
        }

        attributes(root, true)

        fun read(name: String): JsonNode {
            val path = root.resolve(name)
            val before = attributes(path, false)
            val bytes = ByteBuffer.allocate(65_537)
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                while (bytes.hasRemaining() && channel.read(bytes) != -1) { /* bounded read */ }
            }
            val after = attributes(path, false)
            if (before.fileKey() != after.fileKey() || bytes.position() > 65_536) unavailable()
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(bytes.array().copyOf(bytes.position()))?.takeIf { it.isObject } ?: unavailable()
        }

        val registration = read("registration.json")
        val credentials = read("credentials.json")

        fun text(node: JsonNode, key: String): String = node.path(key)
            .takeIf { it.isString && it.asString().isNotBlank() }?.asString() ?: unavailable()

        val clientId = text(registration, "client_id")
        if (clientId == "dynamic_agent_client" || clientId != text(credentials, "client_id") ||
            text(credentials, "issuer") != "https://auth.openai.com") unavailable()
        text(credentials, "subject")
        val scopes = credentials.path("scopes")
        if (!scopes.isArray || scopes.any { !it.isString } ||
            !scopes.toList().map { it.asString() }.containsAll(listOf("resource.invoke", "chatgpt.tokens.use.direct")) ||
            !Instant.parse(text(credentials, "expires_at")).isAfter(clock.instant())) unavailable()
        text(credentials, "access_token")
    } catch (_: Exception) {
        // Filesystem and JSON exceptions may contain secret bytes or identifying paths.
        unavailable()
    }

    private fun unavailable(): Nothing = throw AiOutfitException(503)
}
