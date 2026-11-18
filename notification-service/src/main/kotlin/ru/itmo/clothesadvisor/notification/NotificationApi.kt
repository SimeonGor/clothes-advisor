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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import reactor.core.publisher.Mono

@Tag(name = "Уведомления", description = "Уведомления активного владельца JWT.")
@RequestMapping("/api/me/notifications")
internal interface NotificationApi {
    @GetMapping
    @Operation(summary = "Список уведомлений", description = "Новые первыми. Страницы с 0, от 1 до 50 записей.")
    @ApiResponse(responseCode = "200", description = "Страница уведомлений", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", description = "Общее число уведомлений", schema = Schema(type = "integer", format = "int64"))])
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int): Mono<ResponseEntity<List<Notification>>>

    @GetMapping("/unread-count")
    @Operation(summary = "Число непрочитанных уведомлений")
    fun unread(@AuthenticationPrincipal jwt: Jwt): Mono<Map<String, Long>>

    @PutMapping("/{id}/read")
    @Operation(summary = "Отметить прочитанным", description = "Повтор сохраняет время первого прочтения.")
    @ApiResponse(responseCode = "404", description = "У получателя нет такого уведомления")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): Mono<Void>
}
