package ru.itmo.clothesadvisor.fileservice.controller

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import reactor.core.publisher.Mono

@RequestMapping("/api/files/{fileId}")
internal interface FileApi {
    @PutMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun put(@PathVariable fileId: String, request: ServerHttpRequest): Mono<FileCreated>

    @GetMapping
    fun get(@PathVariable fileId: String): Mono<ResponseEntity<ByteArray>>
}

internal data class FileCreated(val fileId: UUID, val contentType: String, val sizeBytes: Long)
