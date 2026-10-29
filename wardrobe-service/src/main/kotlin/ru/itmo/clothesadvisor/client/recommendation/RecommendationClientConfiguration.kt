package ru.itmo.clothesadvisor.client.recommendation

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction
import org.springframework.context.annotation.Bean
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactivefeign.webclient.WebReactiveFeign
import reactivefeign.webclient.WebReactiveOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import ru.itmo.clothesadvisor.config.LiveClientSettings
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionResponse
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

class RecommendationClientConfiguration {
    @Bean
    internal fun recommendationClient(
        builder: WebClient.Builder,
        loadBalancer: ReactorLoadBalancerExchangeFilterFunction,
        settings: LiveClientSettings,
        breakers: CircuitBreakerRegistry,
    ): RecommendationClient = StreamingRecommendationAdapter(builder, loadBalancer, settings, breakers)
}

internal class StreamingRecommendationAdapter(
    private val builder: WebClient.Builder,
    private val loadBalancer: ReactorLoadBalancerExchangeFilterFunction,
    private val settings: LiveClientSettings,
    private val breakers: CircuitBreakerRegistry,
) : RecommendationClient {
    private val mapper = JsonMapper.builder().findAndAddModules()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    override fun select(request: ItemSelectionRequest) = select(request, false)
    override fun selectCapsule(request: ItemSelectionRequest) = select(request, true)

    private fun select(request: ItemSelectionRequest, capsule: Boolean): ItemSelectionResponse {
        val breakerName = if (capsule) "RecommendationClientselectCapsuleItemSelectionRequest"
            else "RecommendationClientselectItemSelectionRequest"
        try {
            return breakers.circuitBreaker(breakerName).executeSupplier {
                val snapshot = settings.recommendationOptions()
                val client = WebReactiveFeign.builder<StreamingRecommendationClient>(
                    builder.clone()
                        .codecs { it.defaultCodecs().maxInMemorySize(32 * 1024) }
                        .filter(responseFilter(capsule))
                        .filter { original, next ->
                            // Empty discovery never invokes the network exchange.
                            var selected = false
                            loadBalancer.filter(original) { resolved ->
                                selected = true
                                next.exchange(resolved)
                            }.flatMap { response ->
                                if (selected) Mono.just(response) else response.releaseBody().then(
                                    Mono.error(AiSelectionException(AiSelectionFailure.UNAVAILABLE)))
                            }
                        },
                ).options(WebReactiveOptions.Builder()
                    .setConnectTimeoutMillis(snapshot.connectTimeoutMillis().toLong())
                    .setResponseTimeoutMillis(snapshot.readTimeoutMillis().toLong())
                    .setFollowRedirects(false).build())
                    .httpClient(HttpClient.create().disableRetry(true))
                    .objectMapper(mapper)
                    .errorMapper { _, error -> recommendationFailure(error) }
                    .target(StreamingRecommendationClient::class.java, "http://recommendation-service")
                val body = Flux.from(DataBufferUtils.outputStreamPublisher(
                    { output -> mapper.writeValue(output, request) },
                    DefaultDataBufferFactory.sharedInstance,
                    { task -> Thread.startVirtualThread(task) },
                    8192,
                ))
                try {
                    // The synchronous scenario owns admission even if its HTTP worker is interrupted.
                    // join waits for the actual transport termination without cancelling its subscription.
                    (if (capsule) client.selectCapsule(body) else client.select(body))
                        .contextCapture().toFuture().join()
                        ?: throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
                } catch (error: Exception) { throw recommendationFailure(error) }
            }
        } catch (error: Exception) { throw recommendationFailure(error) }
    }

    private fun responseFilter(capsule: Boolean) = ExchangeFilterFunction { request, next ->
        next.exchange(request).flatMap { response ->
            response.bodyToMono(ByteArray::class.java).defaultIfEmpty(byteArrayOf()).map { bytes ->
                validate(response.statusCode().value(), bytes, capsule)
                response.mutate().headers { it.contentType = MediaType.APPLICATION_JSON }
                    .body(Flux.defer { Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)) }).build()
            }
        }
    }

    private fun validate(status: Int, bytes: ByteArray, capsule: Boolean) {
        val body = try { mapper.readTree(bytes) } catch (_: JacksonException) { invalidResponse() }
        if (status != 200) {
            val expected = when (status) {
                422 -> if (capsule) AiSelectionFailure.NO_CAPSULE else AiSelectionFailure.NO_OUTFIT
                502 -> AiSelectionFailure.INVALID_RESPONSE
                503 -> AiSelectionFailure.UNAVAILABLE
                504 -> AiSelectionFailure.TIMEOUT
                else -> null
            }
            if (expected != null && body != null && body.isObject && body.size() == 1 &&
                body.path("code").isString && body.path("code").asString() == expected.name.lowercase()
            ) throw AiSelectionException(expected)
            invalidResponse()
        }
        val ids = body?.path("itemIds")
        val explanation = body?.get("explanation")
        if (body == null || !body.isObject || body.size() != (if (explanation == null) 1 else 2) ||
            ids == null || !ids.isArray || ids.isEmpty || ids.size() > 20 ||
            ids.any { !it.isIntegralNumber || !it.canConvertToLong() || it.longValue() <= 0 } ||
            (explanation != null && (!explanation.isString || explanation.asString().isBlank() ||
                explanation.asString().length > 2000))
        ) invalidResponse()
        if (ids.toList().map { it.longValue() }.distinct().size != ids.size()) invalidResponse()
    }

    private fun invalidResponse(): Nothing = throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
}
