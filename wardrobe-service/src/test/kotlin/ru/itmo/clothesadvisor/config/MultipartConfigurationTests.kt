package ru.itmo.clothesadvisor.config

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader
import org.springframework.http.codec.multipart.MultipartHttpMessageReader
import org.springframework.http.codec.multipart.Part
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ServerWebExchangeDecorator
import org.springframework.web.server.WebFilterChain
import org.springframework.web.server.adapter.DefaultServerWebExchange
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver
import org.springframework.web.server.session.DefaultWebSessionManager
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

class MultipartConfigurationTests {
    @TempDir lateinit var directory: Path
    private val configuration = MultipartConfiguration()
    private val timeout = Duration.ofSeconds(5)
    private val buffers = DefaultDataBufferFactory.sharedInstance
    private val body = (
        "--test\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"photo.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n" + "a".repeat(1024) + "\r\n--test--\r\n"
        ).toByteArray()

    @Test
    fun `malformed content type reaches the HTTP handler unchanged`() {

        // given
        val request = MockServerHttpRequest.post("/").header("Content-Type", "invalid").build()
        val handler = configuration.multipartRequestLimit().apply(HttpHandler { received, _ ->
            assertThat(received).isSameAs(request)
            Mono.empty()
        })

        // when
        val failure = catchThrowable {
            handler.handle(request, MockServerHttpResponse()).block(timeout)
        }

        // then
        assertThat(failure).isNull()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `native temporary files are deleted on completion and failure`(fail: Boolean) {

        // given
        val exchange = exchange(Flux.just(buffers.wrap(body)))
        val failure = IllegalStateException("controller failed")
        val chain = WebFilterChain { resource ->
            resource.multipartData.flatMap {
                assertThat(fileCount()).isEqualTo(1)
                if (fail) Mono.error(failure) else Mono.empty<Void>()
            }
        }

        // when
        val error = catchThrowable {
            configuration.multipartCleanup().filter(exchange, chain).block(timeout)
        }

        // then
        if (fail) assertThat(error).isSameAs(failure) else assertThat(error).isNull()
        assertThat(fileCount()).isZero()
    }

    @Test
    fun `native temporary files are deleted when cancelled after parsing`() {

        // given
        val parsed = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val exchange = observedExchange(Flux.just(buffers.wrap(body)), cleaned)
        val chain = WebFilterChain { resource ->
            resource.multipartData.doOnNext { parsed.countDown() }.then(Mono.never<Void>())
        }
        val subscription = configuration.multipartCleanup().filter(exchange, chain).subscribe()

        // when
        try {
            assertThat(parsed.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(fileCount()).isEqualTo(1)
        } finally {
            subscription.dispose()
        }

        // then
        assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(fileCount()).isZero()
    }

    @Test
    fun `cancellation during cached parsing waits for the late map and deletes its files`() {

        // given
        val started = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val filesAtParseCompletion = AtomicLong(-1)
        val source = Sinks.many().unicast().onBackpressureBuffer<DataBuffer>()
        val exchange = observedExchange(
            source.asFlux().doOnSubscribe { started.countDown() },
            cleaned,
            filesAtParseCompletion,
        )
        val chain = WebFilterChain { resource -> resource.multipartData.then(Mono.never<Void>()) }
        val subscription = configuration.multipartCleanup().filter(exchange, chain).subscribe()
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

        // when
        subscription.dispose()
        assertThat(cleaned.count).isEqualTo(1)
        source.emitNext(buffers.wrap(body), Sinks.EmitFailureHandler.FAIL_FAST)
        source.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)

        // then
        assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(filesAtParseCompletion.get()).isEqualTo(1)
        assertThat(fileCount()).isZero()
    }

    private fun observedExchange(
        body: Flux<DataBuffer>,
        cleaned: CountDownLatch,
        filesAtParseCompletion: AtomicLong = AtomicLong(),
    ) = object : ServerWebExchangeDecorator(exchange(body)) {
        override fun getMultipartData(): Mono<MultiValueMap<String, Part>> =
            super.getMultipartData().doOnNext { filesAtParseCompletion.set(fileCount()) }

        override fun cleanupMultipart(): Mono<Void> =
            super.cleanupMultipart().doOnSuccess { cleaned.countDown() }
    }

    private fun exchange(body: Flux<DataBuffer>): DefaultServerWebExchange {
        val reader = DefaultPartHttpMessageReader().apply {
            setMaxInMemorySize(16)
            setMaxDiskUsagePerPart(10_000_000)
            setFileStorageDirectory(directory)
        }
        val codecs = ServerCodecConfigurer.create().apply {
            registerDefaults(false)
            customCodecs().register(MultipartHttpMessageReader(reader))
        }
        val request = MockServerHttpRequest.post("/api/wardrobe/items")
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=test"))
            .body(body)
        return DefaultServerWebExchange(
            request, MockServerHttpResponse(), DefaultWebSessionManager(), codecs,
            AcceptHeaderLocaleContextResolver(),
        )
    }

    private fun fileCount(): Long = Files.list(directory).use { it.count() }
}
