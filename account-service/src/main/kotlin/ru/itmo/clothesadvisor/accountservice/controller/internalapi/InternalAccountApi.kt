package ru.itmo.clothesadvisor.accountservice.controller.internalapi

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.dto.user.InternalUserResponse

@Hidden
@RequestMapping("/internal")
internal interface InternalAccountApi {
    @GetMapping("/users/{userId}")
    fun user(@PathVariable userId: Long): Mono<InternalUserResponse>

    @GetMapping("/stylist-access/{stylistId}/clients/{ownerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun stylistAccess(@PathVariable stylistId: Long, @PathVariable ownerId: Long): Mono<Void>

    @GetMapping("/admin-access/{adminId}/users/{ownerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun adminAccess(@PathVariable adminId: Long, @PathVariable ownerId: Long): Mono<Void>
}
