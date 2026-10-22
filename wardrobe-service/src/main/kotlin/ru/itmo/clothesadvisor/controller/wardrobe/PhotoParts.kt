package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.Part
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES

internal fun readPhotoParts(parts: List<Part>): Mono<List<ByteArray>> =
    Flux.fromIterable(parts.filterIsInstance<FilePart>())
        .concatMap { part ->
            DataBufferUtils.join(part.content(), MAX_PHOTO_BYTES)
                .map { buffer ->
                    try {
                        ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                    } finally {
                        DataBufferUtils.release(buffer)
                    }
                }
                .defaultIfEmpty(byteArrayOf())
        }
        .collectList()
