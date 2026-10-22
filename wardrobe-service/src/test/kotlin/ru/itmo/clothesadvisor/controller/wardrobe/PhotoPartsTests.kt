package ru.itmo.clothesadvisor.controller.wardrobe

import io.netty.buffer.PooledByteBufAllocator
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.FormFieldPart
import reactor.core.publisher.Flux
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES

class PhotoPartsTests {
    private val buffers = NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT)
    private val timeout = Duration.ofSeconds(5)

    @Test
    fun `file bytes are copied in order and joined buffers are released`() {

        // given
        val first = buffers.allocateBuffer(2).write(byteArrayOf(1, 2))
        val second = buffers.allocateBuffer(1).write(byteArrayOf(3))
        val file = file(Flux.just(first, second))

        // when
        val photos = readPhotoParts(listOf(file)).block(timeout)!!

        // then
        assertThat(photos).hasSize(1)
        assertThat(photos.single()).containsExactly(1, 2, 3)
        assertThat(first.nativeBuffer.refCnt()).isZero()
        assertThat(second.nativeBuffer.refCnt()).isZero()
    }

    @Test
    fun `empty files remain visible to validation and form fields are ignored`() {

        // given
        val empty = file(Flux.empty())
        val field = mock(FormFieldPart::class.java)

        // when
        val photos = readPhotoParts(listOf(field, empty)).block(timeout)!!

        // then
        assertThat(photos).hasSize(1)
        assertThat(photos.single()).isEmpty()
    }

    @Test
    fun `join rejects an oversized photo and releases its buffers`() {

        // given
        val maximum = buffers.allocateBuffer(MAX_PHOTO_BYTES).write(ByteArray(MAX_PHOTO_BYTES))
        val excess = buffers.allocateBuffer(1).write(byteArrayOf(1))
        val file = file(Flux.just(maximum, excess))

        // when
        val failure = catchThrowable { readPhotoParts(listOf(file)).block(timeout) }

        // then
        assertThat(failure).isInstanceOf(DataBufferLimitException::class.java)
        assertThat(maximum.nativeBuffer.refCnt()).isZero()
        assertThat(excess.nativeBuffer.refCnt()).isZero()
    }

    private fun file(content: Flux<out DataBuffer>): FilePart =
        mock(FilePart::class.java).also { `when`(it.content()).thenReturn(Flux.from(content)) }
}
