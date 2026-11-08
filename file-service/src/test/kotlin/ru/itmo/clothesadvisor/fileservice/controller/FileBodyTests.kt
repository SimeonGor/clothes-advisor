package ru.itmo.clothesadvisor.fileservice.controller

import io.netty.buffer.PooledByteBufAllocator
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.NettyDataBufferFactory
import reactor.core.publisher.Flux
import ru.itmo.clothesadvisor.fileservice.model.InvalidPhotoException
import ru.itmo.clothesadvisor.fileservice.model.MAX_PHOTO_BYTES

class FileBodyTests {
    private val buffers = NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT)
    private val timeout = Duration.ofSeconds(5)

    @Test
    fun `copies the body in order and releases every component`() {
        // given
        val first = buffers.allocateBuffer(2).write(byteArrayOf(1, 2))
        val second = buffers.allocateBuffer(1).write(byteArrayOf(3))

        // when
        val bytes = readFileBody(Flux.just(first, second)).block(timeout)

        // then
        assertThat(bytes).containsExactly(1, 2, 3)
        assertThat(first.nativeBuffer.refCnt()).isZero()
        assertThat(second.nativeBuffer.refCnt()).isZero()
    }

    @Test
    fun `both an empty publisher and an empty buffer are invalid`() {
        // given
        val empty = buffers.allocateBuffer(1)

        // when
        val noBuffers = catchThrowable { readFileBody(Flux.empty()).block(timeout) }
        val noBytes = catchThrowable { readFileBody(Flux.just(empty)).block(timeout) }

        // then
        assertThat(noBuffers).isInstanceOf(InvalidPhotoException::class.java)
        assertThat(noBytes).isInstanceOf(InvalidPhotoException::class.java)
        assertThat(empty.nativeBuffer.refCnt()).isZero()
    }

    @Test
    fun `a chunk beyond the limit releases it and all earlier chunks`() {
        // given
        val maximum = buffers.allocateBuffer(MAX_PHOTO_BYTES).write(ByteArray(MAX_PHOTO_BYTES))
        val excess = buffers.allocateBuffer(1).write(byteArrayOf(1))

        // when
        val failure = catchThrowable { readFileBody(Flux.just(maximum, excess)).block(timeout) }

        // then
        assertThat(failure).isInstanceOf(InvalidPhotoException::class.java)
        assertThat(maximum.nativeBuffer.refCnt()).isZero()
        assertThat(excess.nativeBuffer.refCnt()).isZero()
    }

    @Test
    fun `canceling an unfinished upload releases accumulated bytes`() {
        // given
        val first = buffers.allocateBuffer(1).write(byteArrayOf(1))
        val body = Flux.just<DataBuffer>(first).concatWith(Flux.never())
        val subscription = readFileBody(body).subscribe()
        assertThat(first.nativeBuffer.refCnt()).isOne()

        // when
        subscription.dispose()

        // then
        assertThat(first.nativeBuffer.refCnt()).isZero()
    }

    @Test
    fun `upstream error releases bytes accumulated before the failure`() {
        // given
        val first = buffers.allocateBuffer(1).write(byteArrayOf(1))
        val error = IllegalStateException("input failed")
        val body = Flux.just<DataBuffer>(first).concatWith(Flux.error(error))

        // when
        val failure = catchThrowable { readFileBody(body).block(timeout) }

        // then
        assertThat(failure).isSameAs(error)
        assertThat(first.nativeBuffer.refCnt()).isZero()
    }
}
