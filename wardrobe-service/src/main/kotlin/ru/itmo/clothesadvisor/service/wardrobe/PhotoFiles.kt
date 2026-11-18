package ru.itmo.clothesadvisor.service.wardrobe

import java.util.UUID
import reactor.core.publisher.Mono

internal interface PhotoFiles {
    fun put(fileId: UUID, contentType: String, bytes: ByteArray): Mono<FileCreated>

    fun get(fileId: UUID, contentType: String, sizeBytes: Long): Mono<ByteArray>
}

internal data class FileCreated(val fileId: UUID, val contentType: String, val sizeBytes: Long)
