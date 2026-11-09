package ru.itmo.clothesadvisor.client.file

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.config.ExternalClientsConfiguration
import ru.itmo.clothesadvisor.config.FileClientProperties
import ru.itmo.clothesadvisor.config.LiveClientSettings
import ru.itmo.clothesadvisor.config.LiveClientSettingsTests
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException

@SpringBootTest(
    classes = [FileClientTests.ClientApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState",
        "spring.cloud.loadbalancer.cache.enabled=false",
    ],
)
@ActiveProfiles("test", "file-client-test")
class FileClientTests {
    @Configuration(proxyBeanMethods = false)
    @Profile("file-client-test")
    @EnableAutoConfiguration
    @Import(ExternalClientsConfiguration::class, FileClientConfiguration::class)
    class ClientApplication

    @Autowired private lateinit var client: FileClient
    @Autowired private lateinit var settings: LiveClientSettings
    @Autowired private lateinit var discovery: SimpleReactiveDiscoveryProperties

    @BeforeEach
    fun reset() {
        requests.clear()
        settings.apply(LiveClientSettingsTests.source())
    }

    @Test
    fun `discovered service receives Basic and raw bytes for PUT and GET`() {
        // given
        respond = { exchange ->
            if (exchange.requestMethod == "PUT") exchange.reply(201, "application/json", metadata().toByteArray())
            else exchange.reply(200, PNG, PHOTO)
        }

        // when
        val created = client.put(ID, PNG, PHOTO).block(DEADLINE)
        val bytes = client.get(ID, PNG, PHOTO.size.toLong()).block(DEADLINE)

        // then
        assertThat(created).isEqualTo(FileCreated(ID, PNG, PHOTO.size.toLong()))
        assertThat(bytes).isEqualTo(PHOTO)
        assertThat(requests.map { it.method }).containsExactly("PUT", "GET")
        assertThat(requests.map { it.path }).containsOnly("/api/files/$ID")
        assertThat(requests.map { it.auth }).containsOnly(listOf(BASIC))
        assertThat(requests[0].contentType).isEqualTo(PNG)
        assertThat(requests[0].body).isEqualTo(PHOTO)
        assertThat(requests[1].body).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["short", "long", "type", "id", "quoted size", "duplicate", "empty", "malformed", "trailing"])
    fun `PUT rejects invalid confirmations`(variant: String) {
        // given
        val json = when (variant) {
            "short" -> metadata(size = "1")
            "long" -> metadata(size = "100")
            "type" -> metadata(type = "image/jpeg")
            "id" -> metadata(id = UUID.randomUUID())
            "quoted size" -> metadata(size = "\"${PHOTO.size}\"")
            "duplicate" -> metadata().replace("{", "{\"sizeBytes\":1,")
            "empty" -> ""
            "trailing" -> metadata() + "{}"
            else -> "not JSON"
        }
        respond = { it.reply(201, "application/json", json.toByteArray()) }

        // when / then
        assertUnavailable(client.put(ID, PNG, PHOTO))
    }

    @Test
    fun `PUT limits chunked metadata even when it is valid JSON`() {
        // given
        val body = (metadata() + " ".repeat(8192)).toByteArray()
        respond = { it.reply(201, "application/json", body, chunked = true) }

        // when / then
        assertUnavailable(client.put(ID, PNG, PHOTO))
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 401, 403, 404, 409, 503])
    fun `PUT rejects any status other than created without exposing the remote body`(status: Int) {
        // given
        respond = { it.reply(status, "application/json", PASSWORD.toByteArray()) }

        // when / then
        assertUnavailable(client.put(ID, PNG, PHOTO))
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "type", "declared length", "chunked underflow", "chunked overflow", "empty"])
    fun `GET checks status MIME and actual length`(variant: String) {
        // given
        respond = { exchange ->
            when (variant) {
                "status" -> exchange.reply(201, PNG, PHOTO)
                "type" -> exchange.reply(200, "image/jpeg", PHOTO)
                "declared length" -> exchange.reply(200, PNG, PHOTO.copyOf(PHOTO.size + 1))
                "chunked underflow" -> exchange.reply(200, PNG, PHOTO.copyOf(PHOTO.size - 1), true)
                "chunked overflow" -> exchange.reply(200, PNG, PHOTO.copyOf(PHOTO.size + 1), true)
                else -> exchange.reply(200, PNG, byteArrayOf(), true)
            }
        }

        // when / then
        assertUnavailable(client.get(ID, PNG, PHOTO.size.toLong()))
    }

    @Test
    fun `GET accepts exactly the photo limit and rejects one additional chunked byte`() {
        // given
        val bytes = ByteArray(MAX_PHOTO_BYTES) { it.toByte() }
        respond = { it.reply(200, PNG, bytes, chunked = true) }

        // when
        val downloaded = client.get(ID, PNG, MAX_PHOTO_BYTES.toLong()).block(DEADLINE)
        respond = { it.reply(200, PNG, bytes.copyOf(bytes.size + 1), chunked = true) }

        // then
        assertThat(downloaded).isEqualTo(bytes)
        assertUnavailable(client.get(ID, PNG, MAX_PHOTO_BYTES.toLong()))
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 403])
    fun `excess declared length or error status terminates before the body arrives`(status: Int) {
        // given
        val release = CountDownLatch(1)
        respond = {
            it.responseHeaders.add("Content-Type", PNG)
            it.sendResponseHeaders(status, MAX_PHOTO_BYTES + 1L)
            it.responseBody.flush()
            release.await(15, TimeUnit.SECONDS)
        }

        // when / then
        try {
            assertUnavailable(client.get(ID, PNG, PHOTO.size.toLong()))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `disconnect after the PUT body sends exactly once`() {
        // given
        respond = { it.close() }

        // when
        assertUnavailable(client.put(ID, PNG, PHOTO))

        // then
        assertThat(requests).hasSize(1)
        assertThat(requests.single().body).isEqualTo(PHOTO)
    }

    @Test
    fun `missing service instance fails without sending a request`() {
        // given
        val instances = discovery.getInstances("file-service")
        val registered = instances.toList()
        instances.clear()

        // when / then
        try {
            assertUnavailable(client.put(ID, PNG, PHOTO))
            assertUnavailable(client.get(ID, PNG, PHOTO.size.toLong()))
            assertThat(requests).isEmpty()
        } finally {
            instances.addAll(registered)
        }
    }

    @Test
    fun `redirect never sends credentials or bytes to a second address`() {
        // given
        val received = CopyOnWriteArrayList<List<String>?>()
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") {
                received.add(it.requestHeaders["Authorization"])
                it.use { it.reply(201, "application/json", metadata().toByteArray()) }
            }
            start()
        }
        respond = {
            it.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/redirected")
            it.sendResponseHeaders(307, -1)
        }

        // when / then
        try {
            assertUnavailable(client.put(ID, PNG, PHOTO))
            assertThat(requests).hasSize(1)
            assertThat(received).isEmpty()
        } finally {
            other.stop(0)
        }
    }

    @Test
    fun `valid reload affects new subscriptions and invalid reload preserves active settings`() {
        // given
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        respond = {
            entered.countDown()
            if (release.await(15, TimeUnit.SECONDS)) it.reply(200, PNG, PHOTO)
        }
        val pending = client.get(ID, PNG, PHOTO.size.toLong())
        val active = pending.toFuture()

        // when
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val overrides = mapOf(
                "app.file-client.connect-timeout" to "2s",
                "app.file-client.write-timeout" to "3s",
                "app.file-client.read-timeout" to "200ms",
            )
            assertThat(settings.apply(LiveClientSettingsTests.source(overrides))).isTrue()
            val invalid = LiveClientSettingsTests.source(overrides + mapOf(
                "app.file-client.read-timeout" to "90s", "app.file-client.write-timeout" to "0s",
            ))
            assertThat(catchThrowable { settings.apply(invalid) }).isNotNull()

            // then
            assertThat(settings.fileProperties()).isEqualTo(FileClientProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofMillis(200),
            ))
            assertUnavailable(pending)
            assertThat(active).isNotDone()
        } finally {
            release.countDown()
        }
        assertThat(active.get(5, TimeUnit.SECONDS)).isEqualTo(PHOTO)
        assertThat(requests).hasSize(2)
    }

    private fun assertUnavailable(call: Mono<*>) {
        val failure = catchThrowable { call.block(DEADLINE) }
        assertThat(failure).isExactlyInstanceOf(PhotoStorageUnavailableException::class.java).hasNoCause()
        assertThat(failure.stackTraceToString()).doesNotContain(PASSWORD, BASIC)
    }

    companion object {
        private val DEADLINE = Duration.ofSeconds(8)
        private val ID = UUID.fromString("ee9b5216-584d-4941-b86b-5400599e65c6")
        private const val PNG = "image/png"
        private val PHOTO = byteArrayOf(0, -1, 3, 4)
        private const val PASSWORD = " file-client-test-ø "
        private val BASIC = "Basic " + Base64.getEncoder().encodeToString("wardrobe-service:$PASSWORD".toByteArray())
        private val passwordFile = Files.createTempFile("file-client-test-", ".secret").also {
            Files.writeString(it, "$PASSWORD\r\n")
        }
        private data class Request(
            val method: String, val path: String, val auth: List<String>?, val contentType: String?, val body: ByteArray,
        )
        private val requests = CopyOnWriteArrayList<Request>()
        @Volatile private var respond: (HttpExchange) -> Unit = { it.sendResponseHeaders(500, -1) }
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/") { exchange ->
                exchange.use {
                    requests.add(Request(
                        it.requestMethod, it.requestURI.path, it.requestHeaders["Authorization"],
                        it.requestHeaders.getFirst("Content-Type"), it.requestBody.readAllBytes(),
                    ))
                    respond(it)
                }
            }
            start()
        }

        private fun metadata(id: UUID = ID, type: String = PNG, size: String = PHOTO.size.toString()) =
            """{"fileId":"$id","contentType":"$type","sizeBytes":$size}"""

        private fun HttpExchange.reply(status: Int, type: String, bytes: ByteArray, chunked: Boolean = false) {
            responseHeaders.add("Content-Type", type)
            sendResponseHeaders(status, if (chunked) 0L else bytes.size.toLong())
            responseBody.write(bytes)
        }

        @JvmStatic
        @DynamicPropertySource
        fun discovery(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.discovery.client.simple.instances.file-service[0].uri") {
                "http://127.0.0.1:${server.address.port}"
            }
            registry.add("app.file-client.password-file") { passwordFile.toString() }
        }

        @JvmStatic
        @AfterAll
        fun close() {
            server.stop(0)
            executor.close()
            Files.deleteIfExists(passwordFile)
        }
    }
}
