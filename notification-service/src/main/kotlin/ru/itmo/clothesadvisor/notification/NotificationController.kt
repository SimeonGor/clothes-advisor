package ru.itmo.clothesadvisor.notification

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@RestController
@Tag(name = "Уведомления", description = "Уведомления активного владельца JWT.")
@RequestMapping("/api/me/notifications")
internal class NotificationController(private val notifications: Notifications, private val account: ActiveAccount) {
    @GetMapping
    @Operation(summary = "Список уведомлений", description = "Новые первыми. Страницы с 0, от 1 до 50 записей.")
    @ApiResponse(responseCode = "200", description = "Страница уведомлений", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", description = "Общее число уведомлений",
            schema = Schema(type = "integer", format = "int64"))])
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
    @Operation(summary = "Число непрочитанных уведомлений")
    fun unread(@AuthenticationPrincipal jwt: Jwt): Mono<Map<String, Long>> =
        account.requireActive(jwt.subject!!.toLong()).flatMap(notifications::unread).map { mapOf("count" to it) }

    @PutMapping("/{id}/read")
    @Operation(summary = "Отметить прочитанным", description = "Повтор сохраняет время первого прочтения.")
    @ApiResponse(responseCode = "404", description = "У получателя нет такого уведомления")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): Mono<Void> =
        account.requireActive(jwt.subject!!.toLong()).flatMap { notifications.markRead(it, id) }
            .flatMap { found -> if (found) Mono.empty() else Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)) }
}
