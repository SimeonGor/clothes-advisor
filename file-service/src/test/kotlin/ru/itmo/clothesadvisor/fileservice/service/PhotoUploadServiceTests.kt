package ru.itmo.clothesadvisor.fileservice.service

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import ru.itmo.clothesadvisor.fileservice.model.InvalidPhotoException

class PhotoUploadServiceTests {
    private val storage = mock(PhotoStorage::class.java)
    private val service = PhotoUploadService(storage)

    @Test
    fun `PNG and JPEG upload unchanged with canonical MIME including the size limit`() {

        // given
        val png = image("png")
        val jpeg = image("jpeg").copyOf(10_000_000)
        val expectedPng = png.clone()
        val expectedJpeg = jpeg.clone()

        // when
        val pngType = service.upload("png-key", "image/png", png)
        val jpegType = service.upload("jpeg-key", "IMAGE/JPEG", jpeg)

        // then
        assertThat(pngType).isEqualTo("image/png")
        assertThat(jpegType).isEqualTo("image/jpeg")
        verify(storage).put("png-key", pngType, expectedPng)
        verify(storage).put("jpeg-key", jpegType, expectedJpeg)
        verifyNoMoreInteractions(storage)
    }

    @Test
    fun `invalid photos are rejected before storage`() {

        // given
        val png = image("png")
        val invalidPhotos = listOf(
            "image/png" to ByteArray(0),
            "image/png" to byteArrayOf(1),
            "image/png" to png.copyOf(10_000_001),
            "image/png" to png.copyOf(8),
            "image/jpeg" to image("jpeg").copyOf(4),
            "image/png" to png.clone().apply { fill(0, 16, 20) }, // zero width in IHDR
            "image/png" to png.clone().apply { fill(0, 20, 24) }, // zero height in IHDR
            "image/gif" to image("gif"),
            "image/jpeg" to png,
            "image/png" to image("jpeg"),
            "application/octet-stream" to png,
            "" to png,
        )
        invalidPhotos.forEach { (type, bytes) ->

            // when
            val failure = catchThrowable { service.upload("private-key", type, bytes) }

            // then
            assertThat(failure).isInstanceOf(InvalidPhotoException::class.java)
        }
        verifyNoInteractions(storage)
    }

    private fun image(format: String): ByteArray =
        ByteArrayOutputStream().use { output ->
            val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
            check(ImageIO.write(image, format, output))
            output.toByteArray()
        }
}
