package ru.itmo.clothesadvisor.fileservice.repository

import java.util.UUID
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.fileservice.model.FileObject

internal interface FileObjectRepository : ReactiveCrudRepository<FileObject, UUID> {
    @Query(
        """
        INSERT INTO file_object (file_id, object_key, content_type, size_bytes)
        VALUES (:fileId, :objectKey, :contentType, :sizeBytes)
        ON CONFLICT (file_id) DO NOTHING RETURNING *
        """,
    )
    fun insert(fileId: UUID, objectKey: String, contentType: String, sizeBytes: Long): Mono<FileObject>
}
