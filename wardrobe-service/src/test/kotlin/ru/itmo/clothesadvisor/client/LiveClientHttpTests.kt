package ru.itmo.clothesadvisor.client

import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
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
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.client.account.AccountClientTests
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.LiveClientSettings
import ru.itmo.clothesadvisor.config.LiveClientSettingsTests
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure

@SpringBootTest(
    classes = [AccountClientTests.ClientApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test", "account-client-test")
class LiveClientHttpTests {
    @Autowired private lateinit var account: AccountClient
    @Autowired private lateinit var recommendation: RecommendationClient
    @Autowired private lateinit var settings: LiveClientSettings
    @Autowired private lateinit var breakers: CircuitBreakerRegistry
    @Autowired private lateinit var context: ApplicationContext
    private val selection = OutfitSelectionRequest(
        OutfitSelectionWeather(BigDecimal.ZERO, BigDecimal.ZERO, "NONE", "None"),
        listOf(OutfitSelectionCandidate(7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID")),
    )

    @BeforeEach
    fun reset() {
        settings.apply(LiveClientSettingsTests.source())
        breakers.allCircuitBreakers.forEach { it.reset() }
        calls.set(0)
        entered = CountDownLatch(1)
        release = CountDownLatch(0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["account", "recommendation"])
    fun `existing bean uses new timeout while in-flight request completes with captured timeout`(kind: String) {
        // given
        release = CountDownLatch(1)
        val pending = executor.submit<Any> { invoke(kind) }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

        try {
            // when
            settings.apply(LiveClientSettingsTests.source(mapOf("app.$kind-client.read-timeout" to "120ms")))
            val failure = executor.submit<Throwable?> { catchThrowable { invoke(kind) } }.get(5, TimeUnit.SECONDS)

            // then
            assertTimeout(kind, failure)
            assertThat(pending.isDone).isFalse()
            assertThat(calls).hasValue(2)
            assertThat(context.getBean(AccountClient::class.java)).isSameAs(account)
            assertThat(context.getBean(RecommendationClient::class.java)).isSameAs(recommendation)
            release.countDown()
            assertThat(pending.get(5, TimeUnit.SECONDS)).isNotNull()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `same account bean applies smaller response limit on subscription and recovers`() {
        // given
        val notSubscribed = account.getUser(7)

        // when
        settings.apply(LiveClientSettingsTests.source(mapOf("app.account-client.max-response-size" to "32B")))
        val failure = catchThrowable { notSubscribed.block(Duration.ofSeconds(5)) }
        settings.apply(LiveClientSettingsTests.source())
        val recovered = account.getUser(7).block(Duration.ofSeconds(5))

        // then
        assertThat(failure).isExactlyInstanceOf(AccountUnavailableException::class.java)
        assertThat(recovered!!.id).isEqualTo(7)
        assertThat(calls).hasValue(2)
        assertThat(context.getBean(AccountClient::class.java)).isSameAs(account)
    }

    private fun invoke(kind: String): Any = when (kind) {
        "account" -> account.getUser(7).block(Duration.ofSeconds(5))!!
        else -> recommendation.select(selection)
    }

    private fun assertTimeout(kind: String, failure: Throwable?) {
        if (kind == "account") {
            assertThat(failure).isExactlyInstanceOf(AccountUnavailableException::class.java)
        } else {
            assertThat(failure).isInstanceOfSatisfying(AiOutfitException::class.java) {
                assertThat(it.failure).isEqualTo(AiOutfitFailure.TIMEOUT)
            }
        }
        assertThat(failure!!.message).isNull()
        assertThat(failure.cause).isNull()
    }

    companion object {
        private val calls = AtomicInteger()
        @Volatile private var entered = CountDownLatch(1)
        @Volatile private var release = CountDownLatch(0)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/") { exchange ->
                exchange.use {
                    calls.incrementAndGet()
                    it.requestBody.readAllBytes()
                    val gate = release
                    entered.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                    val body = if (it.requestURI.path.startsWith("/internal/")) {
                        """{"id":7,"login":"user","role":"USER","status":"ACTIVE"}"""
                    } else """{"itemIds":[7]}"""
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    it.responseBody.write(body.toByteArray())
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun discovery(registry: DynamicPropertyRegistry) {
            listOf("account", "recommendation").forEach { name ->
                registry.add("spring.cloud.discovery.client.simple.instances.$name-service[0].uri") {
                    "http://127.0.0.1:${server.address.port}"
                }
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
