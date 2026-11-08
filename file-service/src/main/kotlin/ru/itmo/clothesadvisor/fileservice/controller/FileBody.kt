package ru.itmo.clothesadvisor.fileservice.controller

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.fileservice.model.InvalidPhotoException
import ru.itmo.clothesadvisor.fileservice.model.MAX_PHOTO_BYTES

internal fun readFileBody(body: Flux<DataBuffer>): Mono<ByteArray> =
    DataBufferUtils.join(body, MAX_PHOTO_BYTES)
        .map { buffer ->
            try {
                if (buffer.readableByteCount() == 0) throw InvalidPhotoException()
                ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
            } finally {
                DataBufferUtils.release(buffer)
            }
        }
        .switchIfEmpty(Mono.error(InvalidPhotoException()))
        .onErrorMap(DataBufferLimitException::class.java) { InvalidPhotoException() }
