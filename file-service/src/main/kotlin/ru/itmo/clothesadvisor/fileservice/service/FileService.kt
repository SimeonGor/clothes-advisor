package ru.itmo.clothesadvisor.fileservice.service

import java.util.UUID
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.fileservice.model.FileObject
import ru.itmo.clothesadvisor.fileservice.repository.FileObjectRepository
import ru.itmo.clothesadvisor.fileservice.storage.S3PhotoStorage
import ru.itmo.clothesadvisor.fileservice.storage.StoredPhotoNotFoundException

@Service
internal class FileService(
    private val files: FileObjectRepository,
    private val uploads: PhotoUploadService,
    private val storage: S3PhotoStorage,
) {
    fun create(fileId: UUID, type: String, bytes: ByteArray): Mono<FileObject> =
        files.existsById(fileId).flatMap { exists ->
            if (exists) return@flatMap Mono.error(FileAlreadyExistsException())
            val key = UUID.randomUUID().toString()
            Mono.fromCallable { uploads.upload(key, type, bytes) }
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap { canonicalType -> files.insert(fileId, key, canonicalType, bytes.size.toLong()) }
                .switchIfEmpty(Mono.error(FileAlreadyExistsException()))
        }

    fun read(fileId: UUID): Mono<StoredFile> =
        files.findById(fileId)
            .switchIfEmpty(Mono.error(StoredPhotoNotFoundException()))
            .flatMap { file ->
                Mono.fromCallable { StoredFile(file, storage.get(file.objectKey, file.sizeBytes)) }
                    .subscribeOn(Schedulers.boundedElastic())
            }
}

internal class StoredFile(val metadata: FileObject, val bytes: ByteArray)

internal class FileAlreadyExistsException : RuntimeException()
