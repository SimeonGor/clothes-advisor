package ru.itmo.clothesadvisor.client.recommendation

import com.sun.net.httpserver.HttpServer
import feign.Client
import feign.Request
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties
import org.springframework.cloud.openfeign.FeignClientFactory
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.itmo.clothesadvisor.config.RecommendationClientRegistration
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure

@SpringBootTest(
    classes = [RecommendationClientTests.ClientApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test", "recommendation-client-test")
class RecommendationClientTests {
    @Configuration
    @Profile("recommendation-client-test")
    @EnableAutoConfiguration
    @Import(RecommendationClientRegistration::class)
    class ClientApplication

    @Autowired private lateinit var client: RecommendationClient
    @Autowired private lateinit var factory: FeignClientFactory
    @Autowired private lateinit var breakers: CircuitBreakerRegistry
    @Autowired private lateinit var discovery: SimpleDiscoveryProperties
    private val selection = OutfitSelectionRequest(
        OutfitSelectionWeather(BigDecimal("-1.23456789"), BigDecimal.ZERO, "NONE", "None"),
        listOf(OutfitSelectionCandidate(7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID")),
    )

    @BeforeEach
    fun reset() {
        calls.set(0)
        status = 200
        body = """{"itemIds":[7]}"""
        disconnect = false
        gate = null
        breakers.circuitBreaker(BREAKER_NAME).reset()
    }

    @Test
    fun `discovery client uses JDK transport and long read timeout without redirects`() {
        // given
        val transport = factory.getInstance("recommendation-service", Client::class.java)
        val options = factory.getInstance("recommendation-service", Request.Options::class.java)!!

        // when
        val result = client.select(selection)

        // then
        assertThat(result.itemIds).containsExactly(7)
        assertThat(calls).hasValue(1)
        assertThat(transport).isInstanceOf(FeignBlockingLoadBalancerClient::class.java)
        assertThat((transport as FeignBlockingLoadBalancerClient).delegate)
            .isInstanceOf(Client.Default::class.java)
        assertThat(options.connectTimeoutMillis()).isEqualTo(5_000)
        assertThat(options.readTimeoutMillis()).isEqualTo(90_000)
        assertThat(options.isFollowRedirects).isFalse()
        assertThat(received).contains("-1.23456789", "AQID")
        assertThat(breakers.allCircuitBreakers.map { it.name }).containsExactlyInAnyOrder(
            BREAKER_NAME,
            "AccountClientgetUserlong",
            "AccountClientrequireStylistAccesslonglong",
            "AccountClientrequireAdminAccesslonglong",
        )
    }

    @Test
    fun `empty discovery registry is unavailable without HTTP and restored registry recovers`() {
        // given
        val instances = requireNotNull(discovery.instances.remove("recommendation-service"))

        try {
            // when
            val failure = catchThrowable { client.select(selection) }

            // then
            assertFailure(AiOutfitFailure.UNAVAILABLE, failure)
            assertThat(calls).hasValue(0)
        } finally {
            discovery.instances["recommendation-service"] = instances
        }

        // when
        val recovered = client.select(selection)

        // then
        assertThat(recovered.itemIds).containsExactly(7)
        assertThat(calls).hasValue(1)
    }

    @Test
    fun `agreed error codes map once and malformed status pairs fail closed`() {
        // given
        val failures = mapOf(400 to "invalid_request", 422 to "no_outfit", 502 to "invalid_response",
            503 to "unavailable", 504 to "timeout")

        failures.forEach { (code, name) ->
            breakers.circuitBreaker(BREAKER_NAME).reset()
            status = code
            body = """{"code":"$name"}"""

            // when
            val failure = catchThrowable { client.select(selection) }

            // then
            assertFailure(
                if (code == 400) AiOutfitFailure.INVALID_RESPONSE
                else AiOutfitFailure.valueOf(name.uppercase()),
                failure,
            )
        }
        status = 503
        listOf("{}", "secret provider body", """{"code":"timeout"}""", """{"code":"unavailable","detail":"secret"}""").forEach {
            breakers.circuitBreaker(BREAKER_NAME).reset()
            body = it

            // when
            val failure = catchThrowable { client.select(selection) }

            // then
            assertFailure(AiOutfitFailure.INVALID_RESPONSE, failure)
        }
        status = 302
        assertFailure(AiOutfitFailure.INVALID_RESPONSE, catchThrowable { client.select(selection) })
        assertThat(calls).hasValue(10)
    }

    @Test
    fun `malformed success never coerces IDs or accepts empty duplicate or missing lists`() {
        // given
        val invalid = listOf("{}", "null", "not-json", """{"itemIds":[]}""",
            """{"itemIds":[7.1]}""", """{"itemIds":["7"]}""", """{"itemIds":[null]}""",
            """{"itemIds":[7,7]}""", """{"itemIds":[0]}""", """{"itemIds":[7]} {}""")

        invalid.forEach {
            breakers.circuitBreaker(BREAKER_NAME).reset()
            body = it

            // when
            val failure = catchThrowable { client.select(selection) }

            // then
            assertFailure(AiOutfitFailure.INVALID_RESPONSE, failure)
        }
        assertThat(calls).hasValue(invalid.size)
    }

    @Test
    fun `no outfit is ignored but technical errors open breaker without another request`() {
        // given
        status = 422
        body = """{"code":"no_outfit"}"""
        val breaker = breakers.circuitBreaker(BREAKER_NAME)

        // when
        val refusals = (1..3).map { catchThrowable { client.select(selection) } }

        // then
        refusals.forEach { assertFailure(AiOutfitFailure.NO_OUTFIT, it) }
        assertThat(breaker.metrics.numberOfBufferedCalls).isZero()

        // given
        status = 503
        body = """{"code":"unavailable"}"""

        // when
        val failures = (1..3).map { catchThrowable { client.select(selection) } }

        // then
        failures.forEach { assertFailure(AiOutfitFailure.UNAVAILABLE, it) }
        assertThat(calls).hasValue(5)
        assertThat(breaker.state.name).isEqualTo("OPEN")
    }

    @Test
    fun `connection failure is not replayed by transport`() {
        // given
        disconnect = true

        // when
        val failure = catchThrowable { client.select(selection) }

        // then
        assertFailure(AiOutfitFailure.UNAVAILABLE, failure)
        assertThat(calls).hasValue(1)
    }

    @Test
    fun `disabled hidden time limiter allows a delayed valid response`() {
        // given
        val release = CountDownLatch(1)
        gate = release
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            // when
            val pending = executor.submit<List<Long>> { client.select(selection).itemIds }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(catchThrowable { pending.get(30, TimeUnit.MILLISECONDS) })
                .isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            release.countDown()

            // then
            assertThat(pending.get(5, TimeUnit.SECONDS)).containsExactly(7)
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `fallback unwraps timeout transport and no outfit failures without details`() {
        // given
        val request = Request.create(
            Request.HttpMethod.POST, "http://recommendation-service/api/outfit-selections",
            emptyMap(), null, Charsets.UTF_8, null,
        )
        val causes = listOf(
            java.net.SocketTimeoutException("secret") to AiOutfitFailure.TIMEOUT,
            AiOutfitException(AiOutfitFailure.NO_OUTFIT) to AiOutfitFailure.NO_OUTFIT,
            feign.codec.DecodeException(200, "secret", request, java.io.IOException("secret")) to
                AiOutfitFailure.UNAVAILABLE,
        )

        causes.forEach { (cause, expected) ->
            val fallback = RecommendationFallbackFactory().create(java.util.concurrent.ExecutionException(cause))

            // when
            val error = catchThrowable { fallback.select(selection) } as AiOutfitException

            // then
            assertThat(error.failure).isEqualTo(expected)
            assertThat(error.message).isNull()
            assertThat(error.cause).isNull()
        }
    }

    private fun assertFailure(expected: AiOutfitFailure, error: Throwable?) {
        assertThat(error)
            .isInstanceOfSatisfying(AiOutfitException::class.java) {
                assertThat(it.failure).isEqualTo(expected)
                assertThat(it.message).isNull()
                assertThat(it.cause).isNull()
            }
    }

    companion object {
        private const val BREAKER_NAME = "RecommendationClientselectOutfitSelectionRequest"
        private val calls = AtomicInteger()
        @Volatile private var status = 200
        @Volatile private var body = ""
        @Volatile private var received = ""
        @Volatile private var disconnect = false
        @Volatile private var gate: CountDownLatch? = null
        private val entered = CountDownLatch(1)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/api/outfit-selections") { exchange ->
                calls.incrementAndGet()
                received = exchange.requestBody.bufferedReader().readText()
                if (disconnect) {
                    exchange.close()
                } else {
                    gate?.let { entered.countDown(); it.await(5, TimeUnit.SECONDS) }
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
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
            server.stop(0)
            executor.close()
        }
    }
}
