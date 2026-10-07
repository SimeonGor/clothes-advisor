package ru.itmo.clothesadvisor.controller

import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

internal fun toPageRequest(page: Int, size: Int): PageRequest {
    if (page.toLong() * size > Int.MAX_VALUE) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Page offset exceeds the supported range")
    }
    return PageRequest.of(page, size)
}
