package ru.itmo.clothesadvisor.storage.wardrobe

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import ru.itmo.clothesadvisor.config.S3Configuration
import ru.itmo.clothesadvisor.config.S3Properties
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES

class S3PhotoStorageTests {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@S3PhotoStorageTests.executor
        start()
    }
    private val http = S3Configuration().s3HttpClient()
    private val endpoint = "http://127.0.0.1:${server.address.port}"
    private val token = "synthetic-iam-token"
    private val calls = AtomicInteger()
    private val logger = LoggerFactory.getLogger(S3PhotoStorage::class.java) as Logger
    private val originalLevel = logger.level
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.level = Level.DEBUG
        logger.addAppender(logs)
    }

    @AfterEach
    fun close() {
        server.stop(0)
        http.shutdownNow()
        executor.shutdownNow()
        logger.detachAppender(logs)
        logger.level = originalLevel
        logs.stop()
        assertThat(http.awaitTermination(Duration.ofSeconds(5))).isTrue()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `PUT and GET use IAM and preserve the object bytes and conditional create`() {
        val requests = LinkedBlockingQueue<Request>()
        val photo = byteArrayOf(0, 1, 2, -1)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            requests.add(Request(exchange.requestMethod, exchange.requestURI.rawPath,
                exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestHeaders.getFirst("If-None-Match"), exchange.requestBody.use { it.readAllBytes() }))
            exchange.responseHeaders.set("x-amz-request-id", "request-123")
            reply(exchange, 200, if (exchange.requestMethod == "GET") photo else ByteArray(0))
        }
        storage().put("photo-id", "image/png", photo)
        assertThat(storage().get("photo-id", photo.size.toLong())).containsExactly(*photo)
        val put = requests.remove()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.path).isEqualTo("/test-bucket/photo-id")
        assertThat(put.authorization).isEqualTo("Bearer $token")
        assertThat(put.contentType).isEqualTo("image/png")
        assertThat(put.condition).isEqualTo("*")
        assertThat(put.body).containsExactly(*photo)
        val get = requests.remove()
        assertThat(get.method).isEqualTo("GET")
        assertThat(get.path).isEqualTo(put.path)
        assertThat(get.authorization).isEqualTo(put.authorization)
        assertThat(get.condition).isNull()
        assertThat(get.body).isEmpty()
        assertThat(calls).hasValue(2)
        assertThat(logs.list).allSatisfy { event ->
            assertThat(event.level).isEqualTo(Level.DEBUG)
            assertThat(event.formattedMessage).contains("status=200", "bytes=4", "requestId=request-123", "result=success")
            assertThat(event.formattedMessage).doesNotContain(token, endpoint, "photo-id", "Authorization")
            assertThat(event.throwableProxy).isNull()
        }
    }

    @Test
    fun `object key stays in one encoded path segment`() {
        val paths = LinkedBlockingQueue<String>()
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.toASCIIString())
            reply(exchange, 200, byteArrayOf(1))
        }
        storage().get("//other-host/private?query#fragment% /..", 1)
        storage().get("..", 1)
        assertThat(paths.remove()).isEqualTo("/test-bucket/%2F%2Fother-host%2Fprivate%3Fquery%23fragment%25%20%2F..")
        assertThat(paths.remove()).isEqualTo("/test-bucket/%2E%2E")
    }

    @Test
    fun `HTTP errors are sanitized and never retried`() {
        var status = 401
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.close()
            exchange.responseHeaders.set("x-amz-request-id", "bad<>/request-" + "x".repeat(200))
            reply(exchange, status, "private XML provider response".toByteArray())
        }
        for (code in listOf(201, 204, 206, 400, 401, 403, 404, 412, 429, 500, 503)) {
            status = code
            unavailable { storage().get("private-object-key", 3) }
            unavailable { storage().put("private-object-key", "image/png", byteArrayOf(1, 2, 3)) }
        }
        assertThat(calls).hasValue(22)
        assertThat(logs.list).hasSize(22).allSatisfy { event ->
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.formattedMessage).contains("elapsedMs=", "status=", "requestId=bad___request-", "result=http_status")
            assertThat(event.formattedMessage).doesNotContain(token, endpoint, "private-object-key", "private XML", "Authorization")
            assertThat(event.formattedMessage.substringAfter("requestId=").substringBefore(" result=")).hasSize(128)
            assertThat(event.throwableProxy).isNull()
        }
    }

    @Test
    fun `redirect is not followed and does not forward the bearer token`() {
        val forwarded = AtomicInteger()
        server.createContext("/test-bucket/") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.set("Location", "$endpoint/redirect-target")
            reply(exchange, 307, ByteArray(0))
        }
        server.createContext("/redirect-target") { exchange ->
            forwarded.incrementAndGet()
            reply(exchange, 200, byteArrayOf(1))
        }
        unavailable { storage().get("key", 1) }
        unavailable { storage().put("key", "image/png", byteArrayOf(1)) }
        assertThat(calls).hasValue(2)
        assertThat(forwarded).hasValue(0)
    }

    @Test
    fun `GET rejects shorter and oversized bodies including chunked responses`() {
        var body = byteArrayOf(1, 2)
        var chunked = false
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(200, if (chunked) 0 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        unavailable { storage().get("key", 3) }
        body = byteArrayOf(1, 2, 3, 4)
        unavailable { storage().get("key", 3) }
        chunked = true
        unavailable { storage().get("key", 3) }
        assertThat(calls).hasValue(3)
    }

    @Test
    fun `invalid metadata and uploads fail before network`() {
        server.createContext("/") { exchange -> calls.incrementAndGet(); reply(exchange, 200, byteArrayOf(1)) }
        for (size in listOf(-1L, 0L, MAX_PHOTO_BYTES + 1L, Long.MAX_VALUE)) {
            unavailable { storage().get("key", size) }
        }
        unavailable { storage().put("key", "image/png", ByteArray(0)) }
        unavailable { storage().put("key", "image/png", ByteArray(MAX_PHOTO_BYTES + 1)) }
        assertThat(calls).hasValue(0)
    }

    @Test
    fun `PUT and failed responses have bounded discarded bodies`() {
        var status = 200
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            reply(exchange, status, ByteArray(65_537))
        }
        unavailable { storage().put("key", "image/png", byteArrayOf(1)) }
        status = 500
        unavailable { storage().get("key", 1) }
    }

    @Test
    fun `timeout includes delayed headers`() {
        val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        try {
            unavailable { storage(Duration.ofMillis(300)).get("key", 1) }
            assertThat(calls).hasValue(1)
            assertThat(logs.list.single().formattedMessage).contains("result=timeout")
        } finally { release.countDown() }
    }

    @Test
    fun `timeout includes stalled successful response body and retains response diagnostics`() {
        val release = CountDownLatch(1)
        val headers = CountDownLatch(1)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.set("x-amz-request-id", "stalled-request")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(1)
            exchange.responseBody.flush()
            headers.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        try {
            val started = System.nanoTime()
            unavailable { storage(Duration.ofMillis(500)).get("key", 2) }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
            assertThat(headers.count).isZero()
            assertThat(calls).hasValue(1)
            assertThat(logs.list.single().formattedMessage).contains("status=200", "requestId=stalled-request", "result=timeout")
        } finally { release.countDown() }
    }

    @Test
    fun `interruption is preserved and connection failures are sanitized`() {
        Thread.currentThread().interrupt()
        try {
            unavailable { storage().get("key", 1) }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
        server.stop(0)
        unavailable { storage().get("key", 1) }
    }

    private fun storage(timeout: Duration = Duration.ofSeconds(3)) =
        S3PhotoStorage(http, S3Properties(endpoint, "test-bucket", token), timeout)

    private fun unavailable(action: () -> Unit) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PhotoStorageUnavailableException::class.java) {
            assertThat(it.message).isNull()
            assertThat(it.cause).isNull()
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, if (body.isEmpty() || status == 204) -1 else body.size.toLong())
        exchange.responseBody.use { if (status != 204) it.write(body) }
    }

    private data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val condition: String?,
        val body: ByteArray,
    )
}
