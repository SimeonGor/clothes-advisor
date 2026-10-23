package ru.itmo.clothesadvisor.client.account

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction
import org.springframework.context.annotation.Bean
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactivefeign.webclient.WebReactiveFeign
import reactivefeign.webclient.WebReactiveOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

@ConfigurationProperties("app.account-client")
internal data class AccountClientProperties(
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
    val maxResponseSize: DataSize = DataSize.ofMegabytes(1),
)

// Imported explicitly, including in the narrow external-client test contexts.
@EnableConfigurationProperties(AccountClientProperties::class)
internal class AccountClientConfiguration {
    @Bean
    fun accountClient(
        builder: WebClient.Builder,
        loadBalancer: ReactorLoadBalancerExchangeFilterFunction,
        breakers: CircuitBreakerRegistry,
        properties: AccountClientProperties,
        mapper: JsonMapper,
    ): AccountClient {
        val webClient = builder.clone()
            .codecs { it.defaultCodecs().maxInMemorySize(Math.toIntExact(properties.maxResponseSize.toBytes())) }
            .filter(AccountResponseFilter(breakers).filter())
            .filter(loadBalancer)
        val options = WebReactiveOptions.Builder()
            .setConnectTimeoutMillis(properties.connectTimeout.toMillis())
            .setResponseTimeoutMillis(properties.readTimeout.toMillis())
            .setFollowRedirects(false)
            .build()
        return WebReactiveFeign.builder<AccountClient>(webClient)
            .options(options)
            .httpClient(HttpClient.create().disableRetry(true))
            .objectMapper(mapper)
            .errorMapper { _, error -> accountFailure(error) }
            .target(AccountClient::class.java, "http://account-service")
    }
}

private class AccountResponseFilter(private val breakers: CircuitBreakerRegistry) {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun filter() = ExchangeFilterFunction { request, next ->
        val path = request.url().path
        val method = when {
            path.startsWith("/internal/users/") -> "getUserlong"
            path.startsWith("/internal/stylist-access/") -> "requireStylistAccesslonglong"
            path.startsWith("/internal/admin-access/") -> "requireAdminAccesslonglong"
            else -> return@ExchangeFilterFunction Mono.error(AccountUnavailableException())
        }
        Mono.defer {
            next.exchange(request).flatMap { response ->
                response.bodyToMono(ByteArray::class.java).defaultIfEmpty(byteArrayOf())
                    .map { bytes ->
                        validate(response.statusCode().value(), bytes, path, method)
                        response.mutate()
                            .headers { it.contentType = MediaType.APPLICATION_JSON }
                            .body(Flux.defer {
                                Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))
                            })
                            .build()
                    }
            }
        }.onErrorMap { accountFailure(it) }
            .transformDeferred(
                CircuitBreakerOperator.of(breakers.circuitBreaker("AccountClient$method")),
            )
    }

    private fun validate(status: Int, bytes: ByteArray, path: String, method: String) {
        if (status == 404 && bytes.isEmpty()) throw EntityNotFoundException()
        if (status == 403 && method == "requireAdminAccesslonglong") {
            val body = mapper.readTree(bytes)
            if (body != null && body.isObject && body.size() == 1 &&
                body.path("code").isString && body.path("code").asString() == "forbidden"
            ) throw AccessDeniedException()
            throw AccountUnavailableException()
        }
        if (method != "getUserlong") {
            if (status != 204 || bytes.isNotEmpty()) throw AccountUnavailableException()
            return
        }
        if (status != 200) throw AccountUnavailableException()
        val body = mapper.readTree(bytes)
        if (body == null || !body.isObject || body.size() != 4) {
            throw AccountUnavailableException()
        }
        val id = body.path("id")
        val login = body.path("login")
        val role = body.path("role")
        val userStatus = body.path("status")
        if (!id.isIntegralNumber || !id.canConvertToLong() || id.longValue() <= 0 ||
            id.longValue() != path.substringAfterLast('/').toLongOrNull() ||
            !login.isString || login.asString().isBlank() || !role.isString || !userStatus.isString ||
            UserRole.entries.none { it.name == role.asString() } ||
            UserStatus.entries.none { it.name == userStatus.asString() }
        ) throw AccountUnavailableException()
    }
}
