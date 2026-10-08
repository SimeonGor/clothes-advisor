package ru.itmo.clothesadvisor.storage.wardrobe

import java.io.IOException
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.config.S3Properties
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client

@Component
internal class S3PhotoStorage(private val client: S3Client, private val properties: S3Properties) {
    fun put(key: String, contentType: String, bytes: ByteArray) = storageCall {
        client.putObject({ it.bucket(properties.bucket).key(key).contentType(contentType).ifNoneMatch("*") },
            RequestBody.fromBytes(bytes))
        Unit
    }

    fun get(key: String, sizeBytes: Long): ByteArray = storageCall {
        if (sizeBytes !in 1..MAX_PHOTO_BYTES.toLong()) throw PhotoStorageUnavailableException()
        client.getObject { it.bucket(properties.bucket).key(key) }.use { stream ->
            val bytes = stream.readNBytes(sizeBytes.toInt() + 1)
            if (bytes.size.toLong() != sizeBytes) throw PhotoStorageUnavailableException()
            bytes
        }
    }

    private fun <T> storageCall(action: () -> T): T = try {
        action()
    } catch (_: SdkException) {
        throw PhotoStorageUnavailableException()
    } catch (_: IOException) {
        throw PhotoStorageUnavailableException()
    }
}

internal class PhotoStorageUnavailableException : RuntimeException()
