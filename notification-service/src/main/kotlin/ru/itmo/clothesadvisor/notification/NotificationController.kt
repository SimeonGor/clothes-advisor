package ru.itmo.clothesadvisor.notification

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@RestController
internal class NotificationController(private val notifications: Notifications, private val account: ActiveAccount) : NotificationApi {
    override fun list(jwt: Jwt, page: Int, size: Int): Mono<ResponseEntity<List<Notification>>> {
        if (page < 0 || size !in 1..50) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return account.requireActive(jwt.subject!!.toLong()).flatMap { notifications.page(it, page.toLong() * size, size) }
            .map { ResponseEntity.ok().header("X-Total-Count", it.total.toString()).body(it.items) }
    }

    override fun unread(jwt: Jwt): Mono<Map<String, Long>> =
        account.requireActive(jwt.subject!!.toLong()).flatMap(notifications::unread).map { mapOf("count" to it) }

    override fun markRead(jwt: Jwt, id: Long): Mono<Void> =
        account.requireActive(jwt.subject!!.toLong()).flatMap { notifications.markRead(it, id) }
            .flatMap { found -> if (found) Mono.empty() else Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)) }
}
