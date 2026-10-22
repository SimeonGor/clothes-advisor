package ru.itmo.clothesadvisor.accountservice.controller

import org.springframework.data.domain.PageRequest

internal fun currentUserId(header: String?): Long =
    header?.toLongOrNull()?.takeIf { it > 0 } ?: throw InvalidRequestException()

internal fun pageRequest(page: Int, size: Int): PageRequest {
    if (page < 0 || size !in 1..50 || page.toLong() * size > Int.MAX_VALUE) {
        throw InvalidRequestException()
    }
    return PageRequest.of(page, size)
}

internal class InvalidRequestException : RuntimeException()
