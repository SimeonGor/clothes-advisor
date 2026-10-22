package ru.itmo.clothesadvisor.client.account

import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException

@SpringBootTest(
    classes = [AccountClientTests.ClientApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test", "account-client-test", "account-client-timeout-test")
class AccountClientTimeoutTests {
    @Autowired private lateinit var client: AccountClient
    @Autowired private lateinit var breakers: CircuitBreakerRegistry
    @LocalServerPort private var port = 0

    @BeforeEach
    fun reset() {
        calls.set(0)
        responseStatus = 0
        release = CountDownLatch(1)
        bodyStarted = CountDownLatch(1)
        finished = CountDownLatch(1)
        breakers.allCircuitBreakers.forEach { it.reset() }
    }

    @ParameterizedTest
    @CsvSource("user,0", "stylist,0", "admin,0", "user,200", "admin,403")
    fun `timeout before headers or while decoding body fails closed without retry`(
        method: String, status: Int,
    ) {
        // given
        responseStatus = status

        try {
            // when
            val pending = executor.submit<Throwable?> { catchThrowable { invoke(method) } }
            val failure = pending.get(5, TimeUnit.SECONDS)

            // then
            assertUnavailable(failure)
            if (status != 0) assertThat(bodyStarted.count).isZero()
            assertThat(calls).hasValue(1)
        } finally {
            release.countDown()
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @ParameterizedTest
    @CsvSource("user,200", "admin,403")
    fun `truncated JSON in success or denial body fails closed without retry`(
        method: String, status: Int,
    ) {
        // given
        responseStatus = status
        release.countDown()

        try {
            // when
            val failure = catchThrowable { invoke(method) }

            // then
            assertUnavailable(failure)
            assertThat(bodyStarted.count).isZero()
            assertThat(calls).hasValue(1)
        } finally {
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `public timeout response is empty unavailable`() {
        // given
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/account-probe/user"))
            .GET().build()

        try {
            // when
            val response = HttpClient.newHttpClient().use {
                it.send(request, HttpResponse.BodyHandlers.ofString())
            }

            // then
            assertThat(response.statusCode()).isEqualTo(503)
            assertThat(response.body()).isEmpty()
            assertThat(calls).hasValue(1)
        } finally {
            release.countDown()
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun invoke(method: String) {
        when (method) {
            "user" -> client.getUser(7)
            "stylist" -> client.requireStylistAccess(3, 7)
            "admin" -> client.requireAdminAccess(5, 7)
            else -> error("Unknown test method")
        }
    }

    private fun assertUnavailable(failure: Throwable?) {
        assertThat(failure).isExactlyInstanceOf(AccountUnavailableException::class.java)
        assertThat(failure!!.message).isNull()
        assertThat(failure.cause).isNull()
    }

    companion object {
        private val calls = AtomicInteger()
        @Volatile private var responseStatus = 0
        @Volatile private var release = CountDownLatch(1)
        @Volatile private var bodyStarted = CountDownLatch(1)
        @Volatile private var finished = CountDownLatch(1)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/internal/") { exchange ->
                try {
                    exchange.use {
                        calls.incrementAndGet()
                        it.requestBody.readAllBytes()
                        if (responseStatus != 0) {
                            val partialBody = if (responseStatus == 200) """{"id":7,"login":"""
                            else """{"code":"""
                            it.responseHeaders.add("Content-Type", "application/json")
                            it.sendResponseHeaders(responseStatus, 0)
                            it.responseBody.write(partialBody.toByteArray())
                            it.responseBody.flush()
                            bodyStarted.countDown()
                        }
                        release.await(10, TimeUnit.SECONDS)
                    }
                } finally {
                    finished.countDown()
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun discovery(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.discovery.client.simple.instances.account-service[0].uri") {
                "http://127.0.0.1:${server.address.port}"
            }
        }

        @JvmStatic
        @AfterAll
        fun close() {
            release.countDown()
            server.stop(0)
            executor.close()
        }
    }
}
