package ru.itmo.clothesadvisor.notification

import java.time.Duration
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode

@Component
internal class ActiveAccount(builder: WebClient.Builder, loadBalancer: ReactorLoadBalancerExchangeFilterFunction) {
    private val client = builder.clone().baseUrl("http://account-service").filter(loadBalancer).build()

    fun requireActive(subject: Long): Mono<Long> =
        client.get().uri("/internal/users/{id}", subject).exchangeToMono { response ->
            when (response.statusCode().value()) {
                200 -> response.bodyToMono(JsonNode::class.java)
                    .switchIfEmpty(Mono.error(unavailable())).map { user ->
                        val id = user.path("id")
                        if (!id.isIntegralNumber || !id.canConvertToLong() || id.longValue() != subject) throw unavailable()
                        when (user.path("status").asString("")) {
                            "ACTIVE" -> subject
                            "BLOCKED" -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
                            else -> throw unavailable()
                        }
                    }
                404 -> Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED))
                else -> Mono.error(unavailable())
            }
        }.timeout(Duration.ofSeconds(5)).onErrorMap({ it !is ResponseStatusException }) { unavailable() }

    private fun unavailable() = ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
}
