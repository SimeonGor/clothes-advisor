package ru.itmo.clothesadvisor.accountservice.controller

import org.springframework.data.domain.PageRequest

internal fun pageRequest(page: Int, size: Int): PageRequest {
    if (page < 0 || size !in 1..50 || page.toLong() * size > Int.MAX_VALUE) {
        throw InvalidRequestException()
    }
    return PageRequest.of(page, size)
}

internal class InvalidRequestException : RuntimeException()
