package ru.itmo.clothesadvisor.fileservice.service

internal interface PhotoStorage {
    fun put(key: String, contentType: String, bytes: ByteArray)

    fun get(key: String, sizeBytes: Long): ByteArray
}

internal class PhotoStorageUnavailableException : RuntimeException()

internal class StoredPhotoNotFoundException : RuntimeException()
