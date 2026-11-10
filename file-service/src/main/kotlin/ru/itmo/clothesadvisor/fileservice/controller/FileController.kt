package ru.itmo.clothesadvisor.fileservice.controller

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.fileservice.model.InvalidPhotoException
import ru.itmo.clothesadvisor.fileservice.model.MAX_PHOTO_BYTES
import ru.itmo.clothesadvisor.fileservice.service.FileAlreadyExistsException
import ru.itmo.clothesadvisor.fileservice.service.FileService
import ru.itmo.clothesadvisor.fileservice.storage.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.fileservice.storage.StoredPhotoNotFoundException

@RestController
internal class FileController(private val files: FileService) : FileApi {
    override fun put(fileId: String, request: ServerHttpRequest): Mono<FileCreated> {
        val id = parseId(fileId)
        val type = request.headers.getFirst("Content-Type")?.lowercase()
        if (type != "image/png" && type != "image/jpeg") throw InvalidPhotoException()
        if (request.headers.contentLength > MAX_PHOTO_BYTES) throw InvalidPhotoException()
        return readFileBody(request.body)
            .flatMap { bytes -> files.create(id, type, bytes) }
            .map { FileCreated(it.fileId, it.contentType, it.sizeBytes) }
    }

    override fun get(fileId: String): Mono<ResponseEntity<ByteArray>> =
        files.read(parseId(fileId)).map { file ->
            ResponseEntity.ok().contentType(MediaType.parseMediaType(file.metadata.contentType))
                .contentLength(file.metadata.sizeBytes).body(file.bytes)
        }

    private fun parseId(value: String): UUID {
        val id = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        if (!value.equals(id.toString(), ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return id
    }

    @ExceptionHandler(InvalidPhotoException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidPhoto() = Unit

    @ExceptionHandler(FileAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun alreadyExists() = Unit

    @ExceptionHandler(StoredPhotoNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound() = Unit

    @ExceptionHandler(PhotoStorageUnavailableException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun unavailable() = Unit
}
