package ru.itmo.clothesadvisor.client.recommendation

import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionWeather
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure

@SpringBootTest(
    classes = [RecommendationClientTests.ClientApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test", "recommendation-client-test", "recommendation-client-timeout-test")
class RecommendationClientTimeoutTests {
    @Autowired private lateinit var client: RecommendationClient
    @Autowired private lateinit var breakers: CircuitBreakerRegistry
    private val selection = ItemSelectionRequest(
        ItemSelectionWeather(BigDecimal.ZERO, BigDecimal.ZERO, "NONE", "None"),
        listOf(ItemSelectionCandidate(
            7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID",
        )),
    )

    @BeforeEach
    fun reset() {
        calls.set(0)
        release = CountDownLatch(1)
        bodyStarted = CountDownLatch(1)
        finished = CountDownLatch(1)
        breakers.circuitBreaker("RecommendationClientselectItemSelectionRequest").reset()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 200, 503])
    fun `socket timeout before headers or in body is sanitized without retry`(status: Int) {
        // given
        responseStatus = status

        try {
            // when
            val pending = executor.submit<Throwable?> { catchThrowable { client.select(selection) } }
            val failure = pending.get(5, TimeUnit.SECONDS)

            // then
            assertFailure(AiSelectionFailure.TIMEOUT, failure)
            if (status != 0) assertThat(bodyStarted.count).isZero()
            assertThat(calls).hasValue(1)
        } finally {
            release.countDown()
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 503])
    fun `end of incomplete JSON is invalid response without retry`(status: Int) {
        // given
        responseStatus = status
        release.countDown()

        try {
            // when
            val failure = catchThrowable { client.select(selection) }

            // then
            assertFailure(AiSelectionFailure.INVALID_RESPONSE, failure)
            assertThat(bodyStarted.count).isZero()
            assertThat(calls).hasValue(1)
        } finally {
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun assertFailure(expected: AiSelectionFailure, failure: Throwable?) {
        assertThat(failure).isInstanceOfSatisfying(AiSelectionException::class.java) {
            assertThat(it.failure).isEqualTo(expected)
            assertThat(it.message).isNull()
            assertThat(it.cause).isNull()
        }
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
            createContext("/api/outfit-selections") { exchange ->
                try {
                    exchange.use {
                        calls.incrementAndGet()
                        it.requestBody.readAllBytes()
                        if (responseStatus != 0) {
                            val partialBody = if (responseStatus == 200) {
                                """{"itemIds":["""
                            } else {
                                """{"code":"""
                            }
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
            registry.add("spring.cloud.discovery.client.simple.instances.recommendation-service[0].uri") {
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
