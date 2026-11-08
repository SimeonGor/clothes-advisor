package ru.itmo.clothesadvisor.fileservice.model

import java.time.Instant
import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("file_object")
internal data class FileObject(
    @Id val fileId: UUID,
    val objectKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)
