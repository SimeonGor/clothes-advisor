package ru.itmo.clothesadvisor.service.wardrobe

import java.io.IOException
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import org.springframework.http.HttpStatus
import org.springframework.web.multipart.MultipartFile

internal const val MAX_PHOTO_BYTES = 10_000_000
internal const val MAX_ITEM_PHOTOS = 5

internal data class ValidatedPhoto(val s3Key: String, val contentType: String, val bytes: ByteArray)

internal fun validatePhotos(files: List<MultipartFile>, allowEmpty: Boolean): List<ValidatedPhoto> {
    if (files.size > MAX_ITEM_PHOTOS || (!allowEmpty && files.isEmpty())) {
        throw PhotoRequestException(HttpStatus.BAD_REQUEST)
    }
    return files.map { file ->
        if (file.size > MAX_PHOTO_BYTES) {
            throw PhotoRequestException(HttpStatus.PAYLOAD_TOO_LARGE)
        }
        try {
            val bytes = file.inputStream.use { it.readNBytes(MAX_PHOTO_BYTES + 1) }
            if (bytes.size > MAX_PHOTO_BYTES) {
                throw PhotoRequestException(HttpStatus.PAYLOAD_TOO_LARGE)
            }
            if (bytes.isEmpty()) {
                throw PhotoRequestException(HttpStatus.BAD_REQUEST)
            }
            val contentType =
                MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) {
                        throw PhotoRequestException(HttpStatus.BAD_REQUEST)
                    }
                    val reader = readers.next()
                    try {
                        val type =
                            when (reader.formatName.lowercase()) {
                                "jpeg",
                                "jpg" -> "image/jpeg"
                                "png" -> "image/png"
                                else ->
                                    throw PhotoRequestException(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                            }
                        reader.input = input
                        if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                            throw PhotoRequestException(HttpStatus.BAD_REQUEST)
                        }
                        type
                    } finally {
                        reader.dispose()
                    }
                }
            ValidatedPhoto(UUID.randomUUID().toString(), contentType, bytes)
        } catch (_: IOException) {
            throw PhotoRequestException(HttpStatus.BAD_REQUEST)
        }
    }
}

internal class PhotoRequestException(val status: HttpStatus) : RuntimeException()
