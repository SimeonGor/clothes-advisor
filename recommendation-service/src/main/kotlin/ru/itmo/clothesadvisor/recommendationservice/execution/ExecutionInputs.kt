package ru.itmo.clothesadvisor.recommendationservice.execution

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationCandidate

internal class PreparationFailure(val code: String) : RuntimeException()
internal class TemporaryDependencyFailure : RuntimeException()

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("app.ai-worker.enabled", havingValue = "true")
internal class ExecutionInputConfiguration {
    @Bean
    fun executionInputs(
        builder: WebClient.Builder,
        discovery: ReactorLoadBalancerExchangeFilterFunction,
        @Value("\${app.ai-worker.file-password-file:/run/secrets/file-recommendation-password}") filePassword: String,
        @Value("\${app.ai-worker.validation-password-file:/run/secrets/ai-validation-password}") validationPassword: String,
    ): ExecutionInputs {
        fun client(service: String, path: String): WebClient {
            val password = try {
                Files.readString(Path.of(path)).removeSuffix("\n").removeSuffix("\r")
            } catch (_: Exception) {
                throw IllegalStateException("Cannot read AI worker service credential for $service")
            }
            require(password.isNotBlank()) { "Blank AI worker service credential for $service" }
            val encoded = HttpHeaders.encodeBasicAuth("recommendation-service", password, Charsets.UTF_8)
            return builder.clone().baseUrl("http://$service").filter(discovery)
                .defaultHeaders { it.setBasicAuth(encoded) }.build()
        }
        return ExecutionInputs(client("file-service", filePassword), client("wardrobe-service", validationPassword))
    }
}

internal class ExecutionInputs(private val files: WebClient, private val wardrobe: WebClient) {
    fun photo(candidate: RecommendationCandidate): ByteArray =
        files.get().uri("/api/files/{id}", candidate.fileId).exchangeToMono { response ->
            if (response.statusCode().value() == 404) return@exchangeToMono reject(response, PreparationFailure("source_changed"))
            if (response.statusCode().value() != 200) return@exchangeToMono reject(response, TemporaryDependencyFailure())
            if (response.headers().contentType().orElse(null)?.toString() != candidate.contentType ||
                response.headers().contentLength().orElse(-1L) != candidate.sizeBytes
            ) return@exchangeToMono reject(response, PreparationFailure("source_changed"))

            DataBufferUtils.join(response.bodyToFlux(DataBuffer::class.java), candidate.sizeBytes.toInt())
                .map { buffer ->
                    try {
                        if (buffer.readableByteCount().toLong() != candidate.sizeBytes) throw TemporaryDependencyFailure()
                        ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                    } finally { DataBufferUtils.release(buffer) }
                }.switchIfEmpty(Mono.error(TemporaryDependencyFailure()))
        }.asDependency().block()!!

    fun validate(job: ExecutionJob) {
        wardrobe.post().uri("/internal/ai-jobs/{id}/validate-input", job.id)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("requestMonth" to job.month.toString()))
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    204 -> Mono.just(true)
                    403 -> reject(response, PreparationFailure("access_revoked"))
                    404, 409 -> reject(response, PreparationFailure("source_changed"))
                    else -> reject(response, TemporaryDependencyFailure())
                }
            }.asDependency().block()
    }

    private fun <T : Any> reject(response: ClientResponse, failure: RuntimeException): Mono<T> =
        response.bodyToFlux(DataBuffer::class.java).take(0, false).then(Mono.error(failure))

    private fun <T : Any> Mono<T>.asDependency(): Mono<T> = timeout(Duration.ofSeconds(10))
        .onErrorMap({ it !is PreparationFailure && it !is TemporaryDependencyFailure }) { TemporaryDependencyFailure() }
}
