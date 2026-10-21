package ru.itmo.clothesadvisor.client.iam

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class IamTokenClientTests {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val keyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val executor = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@IamTokenClientTests.executor
            start()
        }
    private val endpoint = URI("http://127.0.0.1:${server.address.port}/iam/v1/tokens")
    private val calls = AtomicInteger()
    private val clients = mutableListOf<IamTokenClient>()

    @AfterEach
    fun close() {
        clients.forEach { it.close() }
        server.stop(0)
        executor.shutdownNow()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `authorized key prefix is accepted and exchange carries independently verified PS256 JWT`() {

        // given
        val keyFile = writeKey("key-one", withPrefix = true)
        val requests = LinkedBlockingQueue<Request>()
        server.createContext("/iam/v1/tokens") { exchange ->
            requests.add(
                Request(
                    exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes(),
                ),
            )
            reply(exchange, 200, tokenResponse())
        }
        val client = client(keyFile)

        // when
        val token = client.issueToken()

        // then
        assertThat(token.value).isEqualTo("synthetic-private-token")
        assertThat(token.expiresAt).isEqualTo(now.plusSeconds(3600))
        assertThat(token.toString()).doesNotContain(token.value)
        val request = requests.remove()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.contentType).isEqualTo("application/json")
        val parts = jwtParts(request.body)
        val header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]))
        assertThat(header.get("alg").stringValue()).isEqualTo("PS256")
        assertThat(header.get("typ").stringValue()).isEqualTo("JWT")
        assertThat(header.get("kid").stringValue()).isEqualTo("key-one")
        assertJwtClaims(parts)
        assertJwtSignature(parts)
    }

    @Test
    fun `each issuance rereads the authorized key file after rotation`() {

        // given
        val requests = LinkedBlockingQueue<ByteArray>()
        server.createContext("/iam/v1/tokens") { exchange ->
            requests.add(exchange.requestBody.readAllBytes())
            reply(exchange, 200, tokenResponse())
        }
        val client = client(writeKey("key-one"))
        client.issueToken()

        // when
        writeKey("key-two")
        client.issueToken()

        // then
        val keyIds =
            List(2) {
                val parts = jwtParts(requests.remove())
                mapper.readTree(Base64.getUrlDecoder().decode(parts[0])).get("kid").stringValue()
            }
        assertThat(keyIds).containsExactly("key-one", "key-two")
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "missing",
                "blank",
                "malformed",
                "oversized",
                "missing-id",
                "wrong-type",
                "invalid-pem",
            ],
    )
    fun `unusable keys fail lazily without revealing path contents or causes`(kind: String) {

        // given
        val keyFile = directory.resolve("private-secret-key.json")
        when (kind) {
            "missing",
            "blank" -> Unit
            "malformed" -> Files.writeString(keyFile, "private-secret-malformed")
            "oversized" ->
                Files.writeString(
                    keyFile,
                    mapper.writeValueAsString(keyFields()) + " ".repeat(65_537),
                )
            "missing-id" ->
                Files.writeString(keyFile, mapper.writeValueAsString(keyFields().minus("id")))
            "wrong-type" ->
                Files.writeString(keyFile, mapper.writeValueAsString(keyFields() + ("id" to 123)))
            "invalid-pem" ->
                Files.writeString(
                    keyFile,
                    mapper.writeValueAsString(
                        keyFields() + ("private_key" to "private-invalid-key"),
                    ),
                )
            else -> error("Unknown key fixture: $kind")
        }
        val client = if (kind == "blank") client("") else client(keyFile.toString())
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            reply(exchange, 200, tokenResponse())
        }

        // when
        val failure = catchThrowable { client.issueToken() }

        // then
        assertUnavailable(failure)
        assertThat(calls).hasValue(0)
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "malformed",
                "missing-token",
                "empty-token",
                "header-injection",
                "space",
                "wrong-type",
                "bad-expiry",
                "expired",
                "oversized",
            ],
    )
    fun `invalid responses are bounded and sanitized`(kind: String) {

        // given
        val body =
            when (kind) {
                "malformed" -> "private-secret-error"
                "missing-token" ->
                    mapper.writeValueAsString(
                        mapOf("expiresAt" to now.plusSeconds(3600).toString()),
                    )
                "empty-token" -> tokenResponse(token = "")
                "header-injection" -> tokenResponse(token = "private\r\nInjected: secret")
                "space" -> tokenResponse(token = "private token")
                "wrong-type" ->
                    mapper.writeValueAsString(
                        mapOf("iamToken" to 123, "expiresAt" to now.plusSeconds(3600).toString()),
                    )
                "bad-expiry" -> tokenResponse(expiry = "private-secret-date")
                "expired" -> tokenResponse(expiry = now.toString())
                "oversized" -> tokenResponse() + " ".repeat(65_537)
                else -> error("Unknown response fixture: $kind")
            }
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            reply(exchange, 200, body)
        }
        val client = client(writeKey())

        // when
        val failure = catchThrowable { client.issueToken() }

        // then
        assertUnavailable(failure)
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [201, 401, 429, 500, 307])
    fun `failed status is not retried and redirects do not forward signed JWT`(status: Int) {

        // given
        val forwarded = AtomicInteger()
        server.createContext("/iam/v1/tokens") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.set(
                "Location",
                "http://127.0.0.1:${server.address.port}/redirect",
            )
            reply(exchange, status, "private-secret-provider-error")
        }
        server.createContext("/redirect") { exchange ->
            forwarded.incrementAndGet()
            reply(exchange, 200, tokenResponse())
        }
        val client = client(writeKey())

        // when
        val failure = catchThrowable { client.issueToken() }

        // then
        assertUnavailable(failure)
        assertThat(calls).hasValue(1)
        assertThat(forwarded).hasValue(0)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `deadline includes delayed headers and a stalled chunked response`(sendHeaders: Boolean) {

        // given
        val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            if (sendHeaders) {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
            }
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        val client = client(writeKey(), Duration.ofMillis(500))
        try {
            val started = System.nanoTime()

            // when
            val failure = catchThrowable { client.issueToken() }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)

            // then
            assertUnavailable(failure)
            assertThat(calls).hasValue(1)
            assertThat(elapsed).isLessThan(Duration.ofSeconds(3))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `interruption during exchange is preserved and sanitized`() {

        // given
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val outcome = LinkedBlockingQueue<Pair<Throwable?, Boolean>>()
        server.createContext("/") { exchange ->
            arrived.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        val client = client(writeKey())
        val thread = Thread {
            val failure = catchThrowable { client.issueToken() }
            outcome.add(failure to Thread.currentThread().isInterrupted)
            finished.countDown()
        }
        try {

            // when
            thread.start()
            check(arrived.await(5, TimeUnit.SECONDS))
            thread.interrupt()
            check(finished.await(5, TimeUnit.SECONDS))
            val result = outcome.remove()

            // then
            assertUnavailable(result.first)
            assertThat(result.second).isTrue()
        } finally {
            release.countDown()
            thread.interrupt()
            thread.join(5000)
        }
    }

    private fun jwtParts(requestBody: ByteArray): List<String> {
        val body = mapper.readTree(requestBody)
        assertThat(body.size()).isEqualTo(1)
        val parts = body.get("jwt").stringValue().split('.')
        assertThat(parts).hasSize(3).allSatisfy { assertThat(it).doesNotContain("=") }
        return parts
    }

    private fun assertJwtClaims(parts: List<String>) {
        val claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
        assertThat(claims.get("iss").stringValue()).isEqualTo("service-account")
        assertThat(claims.get("aud").stringValue()).isEqualTo(IAM_TOKEN_ENDPOINT)
        assertThat(claims.get("iat").longValue()).isEqualTo(now.epochSecond)
        assertThat(claims.get("exp").longValue()).isEqualTo(now.plusSeconds(300).epochSecond)
    }

    private fun assertJwtSignature(parts: List<String>) {
        val verifier = Signature.getInstance("RSASSA-PSS")
        verifier.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        verifier.initVerify(keyPair.public)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue()
    }

    private fun writeKey(id: String = "key-id", withPrefix: Boolean = false): String {
        val path = directory.resolve("authorized-key.json")
        Files.writeString(path, mapper.writeValueAsString(keyFields(id, withPrefix)))
        return path.toString()
    }

    private fun keyFields(id: String = "key-id", withPrefix: Boolean = false): Map<String, String> {
        val prefix =
            if (withPrefix) "PLEASE DO NOT REMOVE THIS LINE! Yandex.Cloud SA Key ID $id\n" else ""
        val encoded =
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        return mapOf(
            "id" to id,
            "service_account_id" to "service-account",
            "private_key" to
                "${prefix}-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n",
        )
    }

    private fun client(keyFile: String, timeout: Duration = Duration.ofSeconds(3)) =
        IamTokenClient(keyFile, mapper, clock, endpoint, timeout).also { clients.add(it) }

    private fun tokenResponse(
        token: String = "synthetic-private-token",
        expiry: String = now.plusSeconds(3600).toString(),
    ) = mapper.writeValueAsString(mapOf("iamToken" to token, "expiresAt" to expiry))

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        exchange.requestBody.close()
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun assertUnavailable(failure: Throwable?) {
        assertThat(failure).isInstanceOfSatisfying(IamTokenUnavailableException::class.java) {
            assertThat(it.message).isNull()
            assertThat(it.cause).isNull()
        }
    }

    private data class Request(val method: String, val contentType: String?, val body: ByteArray)
}
