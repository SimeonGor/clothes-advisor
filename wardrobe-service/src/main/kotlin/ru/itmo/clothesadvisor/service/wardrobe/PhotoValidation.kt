package ru.itmo.clothesadvisor.service.wardrobe

import java.io.IOException
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import ru.itmo.clothesadvisor.model.wardrobe.PhotoRequestException
import ru.itmo.clothesadvisor.model.wardrobe.PhotoRequestFailure

internal const val MAX_PHOTO_BYTES = 10_000_000
internal const val MAX_ITEM_PHOTOS = 5

internal data class ValidatedPhoto(val s3Key: String, val contentType: String, val bytes: ByteArray)

internal fun validatePhotos(files: List<ByteArray>, allowEmpty: Boolean): List<ValidatedPhoto> {
    if (files.size > MAX_ITEM_PHOTOS || (!allowEmpty && files.isEmpty())) {
        throw PhotoRequestException(PhotoRequestFailure.INVALID_REQUEST)
    }
    return files.map { bytes ->
        if (bytes.size > MAX_PHOTO_BYTES) {
            throw PhotoRequestException(PhotoRequestFailure.FILE_TOO_LARGE)
        }
        try {
            if (bytes.isEmpty()) {
                throw PhotoRequestException(PhotoRequestFailure.INVALID_REQUEST)
            }
            val contentType =
                MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) {
                        throw PhotoRequestException(PhotoRequestFailure.INVALID_REQUEST)
                    }
                    val reader = readers.next()
                    try {
                        val type =
                            when (reader.formatName.lowercase()) {
                                "jpeg",
                                "jpg" -> "image/jpeg"
                                "png" -> "image/png"
                                else ->
                                    throw PhotoRequestException(PhotoRequestFailure.UNSUPPORTED_FORMAT)
                            }
                        reader.input = input
                        if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                            throw PhotoRequestException(PhotoRequestFailure.INVALID_REQUEST)
                        }
                        type
                    } finally {
                        reader.dispose()
                    }
                }
            ValidatedPhoto(UUID.randomUUID().toString(), contentType, bytes)
        } catch (_: IOException) {
            throw PhotoRequestException(PhotoRequestFailure.INVALID_REQUEST)
        }
    }
}
