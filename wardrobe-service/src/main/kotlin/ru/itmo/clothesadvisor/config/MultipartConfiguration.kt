package ru.itmo.clothesadvisor.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private const val MAX_MULTIPART_BYTES = 51_000_000L

@Configuration
internal class MultipartConfiguration {
    @Bean
    fun multipartRequestLimit() = HttpHandlerDecoratorFactory { delegate ->
        HttpHandler { request, response ->
            if (!isMultipart(request.headers)) {
                delegate.handle(request, response)
            } else {
                // The native multipart reader captures the request when the exchange is created.
                val limited = object : ServerHttpRequestDecorator(request) {
                    override fun getBody(): Flux<DataBuffer> = Flux.defer {
                        var received = 0L
                        super.getBody().handle<DataBuffer> { buffer, sink ->
                            received += buffer.readableByteCount()
                            if (received > MAX_MULTIPART_BYTES) {
                                DataBufferUtils.release(buffer)
                                sink.error(DataBufferLimitException("Multipart request exceeds 51 MB"))
                            } else {
                                sink.next(buffer)
                            }
                        }.doOnDiscard(DataBuffer::class.java, DataBufferUtils::release)
                    }
                }
                delegate.handle(limited, response)
            }
        }
    }

    @Bean
    fun multipartCleanup() = WebFilter { exchange, chain ->
        if (!isMultipart(exchange.request.headers)) {
            chain.filter(exchange)
        } else {
            Mono.usingWhen(
                Mono.just(exchange),
                { chain.filter(it) },
                { it.cleanupMultipart() },
                { resource, _ -> resource.cleanupMultipart() },
                { resource ->
                    // Native parsing is cached and may finish after downstream cancellation.
                    resource.multipartData.onErrorComplete().then(resource.cleanupMultipart())
                },
            )
        }
    }

    private fun isMultipart(headers: HttpHeaders): Boolean =
        try {
            headers.contentType?.isCompatibleWith(MediaType.MULTIPART_FORM_DATA) == true
        } catch (_: InvalidMediaTypeException) {
            // Leave malformed media types to the HTTP handler's normal error mapping.
            false
        }
}
