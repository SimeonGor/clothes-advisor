package ru.itmo.clothesadvisor.client.recommendation

import ru.itmo.clothesadvisor.config.idempotencyService

import com.sun.net.httpserver.HttpServer
import feign.Client
import feign.Request
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.refEq
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryProperties
import org.springframework.cloud.openfeign.FeignClientFactory
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.config.ExternalClientsConfiguration
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto
import ru.itmo.clothesadvisor.model.weather.PrecipitationType
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.ai.AiOutfitService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

@SpringBootTest(
    classes = [RecommendationClientTests.ClientApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test", "recommendation-client-test")
class RecommendationClientTests {
    @Configuration
    @Profile("recommendation-client-test")
    @EnableAutoConfiguration
    @Import(ExternalClientsConfiguration::class)
    class ClientApplication

    @Autowired private lateinit var client: RecommendationClient
    @Autowired private lateinit var factory: FeignClientFactory
    @Autowired private lateinit var breakers: CircuitBreakerRegistry
    @Autowired private lateinit var discovery: SimpleReactiveDiscoveryProperties
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
        entered = CountDownLatch(1)
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
        assertThat(result.explanation).isNull()
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
        val instances = discovery.getInstances("recommendation-service")
        val registered = instances.toList()
        instances.clear()

        try {
            // when
            val failure = catchThrowable { client.select(selection) }

            // then
            assertFailure(AiOutfitFailure.UNAVAILABLE, failure)
            assertThat(calls).hasValue(0)
        } finally {
            instances.addAll(registered)
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
    fun `new response preserves explanation and rejects invalid or additional fields`() {
        for (explanation in listOf("  Подходит для дождя.\nТёплый слой.  ", "я".repeat(2000))) {
            // given
            body = tools.jackson.databind.json.JsonMapper().writeValueAsString(
                mapOf("itemIds" to listOf(7), "explanation" to explanation),
            )

            // when
            val result = client.select(selection)

            // then
            assertThat(result.itemIds).containsExactly(7)
            assertThat(result.explanation).isEqualTo(explanation)
        }
        val invalid = listOf("null", "42", "true", "[]", "{}", "\"\"", "\" \\n\\t\"", "\"${"я".repeat(2001)}\"")
            .map { """{"itemIds":[7],"explanation":$it}""" } + listOf(
                """{"itemIds":[7],"explanation":"Тепло","extra":true}""",
                """{"itemIds":[7],"extra":true}""",
                """{"itemIds":[7],"explanation":"Тепло","explanation":"Удобно"}""",
                """{"itemIds":[7],"itemIds":[7],"explanation":"Тепло"}""",
            )
        invalid.forEach { json ->
            // given
            breakers.circuitBreaker(BREAKER_NAME).reset()
            body = json

            // when
            val failure = catchThrowable { client.select(selection) }

            // then
            assertFailure(AiOutfitFailure.INVALID_RESPONSE, failure)
        }
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
    fun `cancelling a subscriber keeps admission occupied until the real Feign call exits`() {
        // given
        val users = mock(CurrentUserService::class.java)
        val items = mock(WardrobeItemRepository::class.java)
        val photos = mock(WardrobeItemPhotoRepository::class.java)
        val precipitation = mock(PrecipitationTypeRepository::class.java)
        val content = mock(WardrobeItemPhotoService::class.java)
        val outfits = mock(OutfitService::class.java)
        val transactions = mock(PlatformTransactionManager::class.java)
        val meters = SimpleMeterRegistry()
        val storedAt = Instant.parse("2024-02-03T04:05:06Z")
        val weather = OutfitWeatherDto(BigDecimal.ONE, 1, BigDecimal.ZERO)
        val request = CreateAiOutfitRequest("Daily", weather, null)
        val saved = OutfitResponse(80, 7, 7, OutfitSource.AI, "Daily", listOf(7), weather, storedAt)
        val item = WardrobeItem(
            7, WardrobeCategory("TOP", "Top"), "Shirt", "White", "Cotton", storedAt,
        ).apply { id = 7 }
        val photo = WardrobeItemPhoto(7, "key", "image/png", 3, storedAt).apply { id = 21 }
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        `when`(users.requireActive(7)).thenReturn(CurrentUser(7, "user", UserRole.USER))
        `when`(precipitation.findById(1)).thenReturn(PrecipitationType("NONE", "None"))
        `when`(items.findAvailableOwnedItemsWithPhotos(7, 21)).thenReturn(listOf(item))
        `when`(items.lockOwnedItemsForShare(7, listOf(7))).thenReturn(listOf(item))
        `when`(photos.findFirstByItemIdOrderByIdAsc(7)).thenReturn(photo)
        `when`(photos.findByIdAndItemIdAndItemOwnerId(21, 7, 7)).thenReturn(photo)
        `when`(content.getContent(7, 7, 21))
            .thenReturn(WardrobeItemPhotoContent("image/png", byteArrayOf(1, 2, 3)))
        val outfitRequest = CreateOutfitRequest("Daily", listOf(7), weather)
        `when`(outfits.createAi(eq(7L), refEq(outfitRequest) ?: outfitRequest, org.mockito.Mockito.isNull(), org.mockito.Mockito.isNull(), org.mockito.Mockito.isNull(), org.mockito.Mockito.isNull()))
            .thenReturn(saved)
        val service = AiOutfitService(
            users, items, photos, precipitation,
            mock(ru.itmo.clothesadvisor.repository.preferences.StylePreferencesRepository::class.java),
            content, client, outfits, idempotencyService(transactions), transactions, meters, "user",
        )
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        gate = release
        val scheduler = Schedulers.newBoundedElastic(1, 10, "ai-feign-admission-test")
        val creation = Mono.fromCallable {
                try {
                    service.create(7, request)
                } finally {
                    exited.countDown()
                }
            }
            .subscribeOn(scheduler)
            .toFuture()
        try {
            // when
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(creation.cancel(true)).isTrue()

            // then
            assertThat(exited.await(250, TimeUnit.MILLISECONDS)).isFalse()

            // when
            val busy = catchThrowable { service.create(7, request) }

            // then
            assertFailure(AiOutfitFailure.UNAVAILABLE, busy)
            assertThat(exited.count).isEqualTo(1)
            assertThat(calls).hasValue(1)
            verify(items).findAvailableOwnedItemsWithPhotos(7, 21)
            verify(photos).findFirstByItemIdOrderByIdAsc(7)
            verify(content).getContent(7, 7, 21)
            verifyNoInteractions(outfits)
            assertThat(meters.get("wardrobe.ai.active").gauge().value()).isEqualTo(1.0)
            assertThat(meters.get("wardrobe.ai.rejected").counter().count()).isEqualTo(1.0)
            assertThat(meters.get("wardrobe.ai.duration").timers()).allSatisfy {
                assertThat(it.count()).isZero()
            }

            // when
            release.countDown()
            assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue()
            val recovered = service.create(7, request)

            // then
            assertThat(recovered).isSameAs(saved)
            assertThat(calls).hasValue(2)
            verify(content, times(2)).getContent(7, 7, 21)
            assertThat(meters.get("wardrobe.ai.active").gauge().value()).isZero()
            assertThat(meters.get("wardrobe.ai.duration").tag("outcome", "success").timer().count())
                .isEqualTo(2)
        } finally {
            release.countDown()
            creation.cancel(true)
            exited.await(10, TimeUnit.SECONDS)
            scheduler.dispose()
            meters.close()
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
        @Volatile private var entered = CountDownLatch(1)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/api/outfit-selections") { exchange ->
                calls.incrementAndGet()
                received = exchange.requestBody.bufferedReader().readText()
                if (disconnect) {
                    exchange.close()
                } else {
                    gate?.let { entered.countDown(); check(it.await(30, TimeUnit.SECONDS)) }
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
