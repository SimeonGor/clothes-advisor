package ru.itmo.clothesadvisor.fileservice.service

import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.fileservice.model.InvalidPhotoException
import ru.itmo.clothesadvisor.fileservice.model.MAX_PHOTO_BYTES
import ru.itmo.clothesadvisor.fileservice.storage.S3PhotoStorage

@Service
internal class PhotoUploadService(private val storage: S3PhotoStorage) {
    fun upload(key: String, declaredContentType: String, bytes: ByteArray): String {
        if (bytes.size !in 1..MAX_PHOTO_BYTES) throw InvalidPhotoException()
        val contentType = detectContentType(bytes)
        if (declaredContentType.lowercase() != contentType) throw InvalidPhotoException()
        storage.put(key, contentType, bytes)
        return contentType
    }

    private fun detectContentType(bytes: ByteArray): String =
        try {
            MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) throw InvalidPhotoException()
                val reader = readers.next()
                try {
                    val type = when (reader.formatName.lowercase()) {
                        "jpeg", "jpg" -> "image/jpeg"
                        "png" -> "image/png"
                        else -> throw InvalidPhotoException()
                    }
                    reader.input = input
                    if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                        throw InvalidPhotoException()
                    }
                    type
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: IOException) {
            throw InvalidPhotoException()
        }
}
