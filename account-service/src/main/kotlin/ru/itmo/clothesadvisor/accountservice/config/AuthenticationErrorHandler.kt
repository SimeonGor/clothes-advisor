package ru.itmo.clothesadvisor.accountservice.config

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono

@Component
@Order(-2)
internal class AuthenticationErrorHandler : WebExceptionHandler {
    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> =
        if (ex is AccountAuthenticationUnavailableException && !exchange.response.isCommitted) {
            securityError(exchange, 503)
        } else Mono.error(ex)
}
