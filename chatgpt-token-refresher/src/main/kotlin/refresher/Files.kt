package refresher

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal val json = JsonMapper.builder()
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    .build()
internal val ownerFile = PosixFilePermissions.fromString("rw-------")
internal val ownerDirectory = PosixFilePermissions.fromString("rwx------")
internal const val MAX_BYTES = 65_536
internal const val MAX_TRUST_BYTES = 1_048_576

// No provider body, token, identifying path or nested exception reaches diagnostics.
internal class Failure(val category: String) : RuntimeException(category, null, false, false)
internal fun fail(category: String): Nothing = throw Failure(category)
internal fun JsonNode.text(name: String): String = path(name)
    .takeIf { it.isString && it.asString().isNotBlank() }?.asString() ?: fail("invalid_record")

internal class Records(val directory: Path) {
    init {
        var ancestor = directory.toAbsolutePath().root
        for (part in directory.toAbsolutePath()) {
            ancestor = ancestor.resolve(part)
            if (Files.isSymbolicLink(ancestor) || !Files.isDirectory(ancestor, NOFOLLOW_LINKS)) fail("unsafe_storage")
        }
        if (Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) != ownerDirectory) fail("unsafe_storage")
    }

    private fun check(path: Path, certificateInput: Boolean = false) {
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            Files.getOwner(path, NOFOLLOW_LINKS) != Files.getOwner(directory, NOFOLLOW_LINKS) ||
            (if (certificateInput) permissions.any { it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.OTHERS_WRITE }
                else permissions != ownerFile)
        ) fail("unsafe_storage")
    }

    fun bytes(name: String): ByteArray? = readBytes(name, MAX_BYTES, false)
    fun certificates(name: String): ByteArray? = readBytes(name, MAX_TRUST_BYTES, true)

    private fun readBytes(name: String, limit: Int, certificateInput: Boolean): ByteArray? {
        val path = directory.resolve(name)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        check(path, certificateInput)
        val buffer = ByteBuffer.allocate(limit + 1)
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use {
            while (buffer.hasRemaining() && it.read(buffer) != -1) { /* bounded read */ }
        }
        if (buffer.position() > limit) fail("record_too_large")
        return buffer.array().copyOf(buffer.position())
    }

    fun read(name: String): JsonNode? = bytes(name)?.let {
        try { json.readTree(it)?.takeIf { node -> node.isObject } ?: fail("invalid_record") }
        catch (_: Exception) { fail("invalid_record") }
    }

    fun write(name: String, value: Any) = writeBytes(name, json.writeValueAsBytes(value))

    fun writeBytes(name: String, bytes: ByteArray) = writeBounded(name, bytes, MAX_BYTES)
    fun writeCertificates(name: String, bytes: ByteArray) = writeBounded(name, bytes, MAX_TRUST_BYTES)

    private fun writeBounded(name: String, bytes: ByteArray, limit: Int) {
        if (bytes.size > limit) fail("record_too_large")
        val destination = directory.resolve(name)
        if (Files.exists(destination, NOFOLLOW_LINKS)) check(destination)
        val temporary = directory.resolve(".pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(temporary, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(ownerFile)).use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            // Persist the rename before any network or public write depends on it.
            FileChannel.open(directory, READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary) }
    }

    fun delete(name: String) {
        val path = directory.resolve(name)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            check(path)
            Files.delete(path)
        }
    }

    fun <T> locked(action: () -> T): T {
        val guard = directory.resolve("command.guard")
        try { Files.createDirectory(guard, PosixFilePermissions.asFileAttribute(ownerDirectory)) }
        catch (_: java.nio.file.FileAlreadyExistsException) { fail("busy") }
        try {
            val path = directory.resolve("command.lock")
            if (!Files.exists(path, NOFOLLOW_LINKS)) {
                try { Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerFile)) }
                catch (_: java.nio.file.FileAlreadyExistsException) { /* validate below */ }
            }
            check(path)
            FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() }
                    catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) fail("busy")
                lock.use { return action() }
            }
        } finally { Files.delete(guard) }
    }
}
