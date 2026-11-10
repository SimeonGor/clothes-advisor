package ru.itmo.clothesadvisor.notification

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/me/notifications")
internal class NotificationController(private val notifications: Notifications, private val account: ActiveAccount) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): Mono<ResponseEntity<List<Notification>>> {
        if (page < 0 || size !in 1..50) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return account.requireActive(jwt.subject!!.toLong()).flatMap { notifications.page(it, page.toLong() * size, size) }
            .map { ResponseEntity.ok().header("X-Total-Count", it.total.toString()).body(it.items) }
    }

    @GetMapping("/unread-count")
    fun unread(@AuthenticationPrincipal jwt: Jwt): Mono<Map<String, Long>> =
        account.requireActive(jwt.subject!!.toLong()).flatMap(notifications::unread).map { mapOf("count" to it) }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): Mono<Void> =
        account.requireActive(jwt.subject!!.toLong()).flatMap { notifications.markRead(it, id) }
            .flatMap { found -> if (found) Mono.empty() else Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)) }
}
