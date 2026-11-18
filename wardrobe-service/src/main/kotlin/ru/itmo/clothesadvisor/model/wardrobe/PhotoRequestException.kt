package ru.itmo.clothesadvisor.model.wardrobe

internal enum class PhotoRequestFailure {
    INVALID_REQUEST,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    CAPACITY_EXCEEDED,
}

internal class PhotoRequestException(val failure: PhotoRequestFailure) : RuntimeException()
