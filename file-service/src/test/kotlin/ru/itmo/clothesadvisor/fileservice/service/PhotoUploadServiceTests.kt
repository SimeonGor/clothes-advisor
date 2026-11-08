package ru.itmo.clothesadvisor.fileservice.service

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import ru.itmo.clothesadvisor.fileservice.client.iam.IamTokenProvider
import ru.itmo.clothesadvisor.fileservice.config.S3Configuration
import ru.itmo.clothesadvisor.fileservice.config.S3Properties
import ru.itmo.clothesadvisor.fileservice.model.InvalidPhotoException
import ru.itmo.clothesadvisor.fileservice.storage.S3PhotoStorage

class PhotoUploadServiceTests {
    private val uploads = LinkedBlockingQueue<Upload>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            uploads.add(
                Upload(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.use { it.readAllBytes() },
                ),
            )
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }
    private val http = S3Configuration().s3HttpClient()
    private val tokenProvider = mock(IamTokenProvider::class.java)
    private val service = PhotoUploadService(
        S3PhotoStorage(
            http,
            S3Properties("http://127.0.0.1:${server.address.port}", "test-bucket"),
            tokenProvider,
        ),
    )

    @AfterEach
    fun close() {
        server.stop(0)
        http.close()
    }

    @Test
    fun `PNG and JPEG upload unchanged with canonical MIME including the size limit`() {

        // given
        `when`(tokenProvider.accessToken()).thenReturn("synthetic-token")
        val png = image("png")
        val jpeg = image("jpeg").copyOf(10_000_000)

        // when
        service.upload("png-key", "image/png", png)
        service.upload("jpeg-key", "IMAGE/JPEG", jpeg)

        // then
        verify(tokenProvider, times(2)).accessToken()
        assertThat(uploads).hasSize(2)
        val pngUpload = uploads.remove()
        assertThat(pngUpload.method).isEqualTo("PUT")
        assertThat(pngUpload.path).isEqualTo("/test-bucket/png-key")
        assertThat(pngUpload.type).isEqualTo("image/png")
        assertThat(pngUpload.bytes).containsExactly(*png)
        val jpegUpload = uploads.remove()
        assertThat(jpegUpload.method).isEqualTo("PUT")
        assertThat(jpegUpload.path).isEqualTo("/test-bucket/jpeg-key")
        assertThat(jpegUpload.type).isEqualTo("image/jpeg")
        assertThat(jpegUpload.bytes.contentEquals(jpeg)).isTrue()
    }

    @Test
    fun `invalid photos are rejected before obtaining a token or sending an HTTP request`() {

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
        verifyNoInteractions(tokenProvider)
        assertThat(uploads).isEmpty()
    }

    private fun image(format: String): ByteArray =
        ByteArrayOutputStream().use { output ->
            val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
            check(ImageIO.write(image, format, output))
            output.toByteArray()
        }

    private data class Upload(
        val method: String,
        val path: String,
        val type: String?,
        val bytes: ByteArray,
    )
}
