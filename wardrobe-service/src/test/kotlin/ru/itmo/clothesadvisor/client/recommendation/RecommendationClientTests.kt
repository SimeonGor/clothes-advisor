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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import ru.itmo.clothesadvisor.config.LiveClientSettings
import ru.itmo.clothesadvisor.config.LiveClientSettingsTests
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionStylePreferences
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionWeather
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
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
import ru.itmo.clothesadvisor.service.ai.AiSelectionService
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
    @Autowired private lateinit var settings: LiveClientSettings
    @Autowired private lateinit var discovery: SimpleReactiveDiscoveryProperties
    private val selection = ItemSelectionRequest(
        ItemSelectionWeather(BigDecimal("-1.23456789"), BigDecimal.ZERO, "NONE", "None"),
        listOf(ItemSelectionCandidate(7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID")),
    )

    @BeforeEach
    fun reset() {
        calls.set(0)
        status = 200
        body = """{"itemIds":[7]}"""
        disconnect = false
        gate = null
        entered = CountDownLatch(1)
        settings.apply(LiveClientSettingsTests.source(listOf(BREAKER_NAME, CAPSULE_BREAKER_NAME).flatMap {
            listOf(
                "resilience4j.circuitbreaker.instances.$it.minimum-number-of-calls" to "2",
                "resilience4j.circuitbreaker.instances.$it.sliding-window-size" to "2",
            )
        }.toMap()))
        breakers.allCircuitBreakers.forEach { it.reset() }
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
        assertThat(receivedPath).isEqualTo("/api/outfit-selections")
        assertThat(breakers.allCircuitBreakers.map { it.name }).containsExactlyInAnyOrder(
            BREAKER_NAME,
            CAPSULE_BREAKER_NAME,
            "AccountClientgetUserlong",
            "AccountClientrequireStylistAccesslonglong",
            "AccountClientrequireAdminAccesslonglong",
        )
        assertThat(breakers.circuitBreaker(BREAKER_NAME).metrics.numberOfSuccessfulCalls).isEqualTo(1)
    }

    @Test
    fun `capsule uses its own POST path and preserves the shared JSON contract`() {
        // given
        body = """{"itemIds":[7],"explanation":"Несколько сочетаний."}"""
        val request = selection.copy(
            occasion = "Работа", preferredStyle = "Классика",
            stylePreferences = ItemSelectionStylePreferences("Белый", "Шерсть", "Удобно"),
        )

        // when
        val result = client.selectCapsule(request)

        // then
        assertThat(result.itemIds).containsExactly(7)
        assertThat(result.explanation).isEqualTo("Несколько сочетаний.")
        assertThat(calls).hasValue(1)
        assertThat(receivedPath).isEqualTo("/api/capsule-selections")
        assertThat(receivedMethod).isEqualTo("POST")
        val mapper = tools.jackson.databind.json.JsonMapper()
        assertThat(mapper.readTree(received)).isEqualTo(mapper.readTree("""
            {"weather":{"temperatureC":-1.23456789,"windSpeedMps":0,"precipitationCode":"NONE","precipitationName":"None"},
             "candidates":[{"itemId":7,"name":"Shirt","categoryCode":"TOP","categoryName":"Top","color":"White","material":"Cotton","contentType":"image/png","imageBase64":"AQID"}],
             "occasion":"Работа","preferredStyle":"Классика",
             "stylePreferences":{"preferredColorsAndMaterials":"Белый","avoidances":"Шерсть","additionalWishes":"Удобно"}}
        """))
        assertThat(breakers.circuitBreaker(CAPSULE_BREAKER_NAME).metrics.numberOfSuccessfulCalls).isEqualTo(1)
        assertThat(breakers.circuitBreaker(BREAKER_NAME).metrics.numberOfBufferedCalls).isZero()
        assertThat(breakers.allCircuitBreakers.map { it.name }).containsExactlyInAnyOrderElementsOf(
            LiveClientSettings.BREAKER_NAMES,
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `empty discovery registry is unavailable without HTTP and restored registry recovers`(capsule: Boolean) {
        // given
        val instances = discovery.getInstances("recommendation-service")
        val registered = instances.toList()
        instances.clear()

        try {
            // when
            val failure = catchThrowable { select(capsule) }

            // then
            assertFailure(AiSelectionFailure.UNAVAILABLE, failure)
            assertThat(calls).hasValue(0)
        } finally {
            instances.addAll(registered)
        }

        // when
        val recovered = select(capsule)

        // then
        assertThat(recovered.itemIds).containsExactly(7)
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `agreed error codes map once and malformed status pairs fail closed`(capsule: Boolean) {
        // given
        val breaker = breakers.circuitBreaker(if (capsule) CAPSULE_BREAKER_NAME else BREAKER_NAME)
        val failures = mapOf(
            400 to "invalid_request", 422 to (if (capsule) "no_capsule" else "no_outfit"),
            502 to "invalid_response", 503 to "unavailable", 504 to "timeout",
        )

        failures.forEach { (code, name) ->
            breaker.reset()
            status = code
            body = """{"code":"$name"}"""

            // when
            val failure = catchThrowable { select(capsule) }

            // then
            assertFailure(
                if (code == 400) AiSelectionFailure.INVALID_RESPONSE
                else AiSelectionFailure.valueOf(name.uppercase()),
                failure,
            )
        }
        status = 503
        listOf("{}", "secret provider body", """{"code":"timeout"}""", """{"code":"unavailable","detail":"secret"}""").forEach {
            breaker.reset()
            body = it

            // when
            val failure = catchThrowable { select(capsule) }

            // then
            assertFailure(AiSelectionFailure.INVALID_RESPONSE, failure)
        }
        status = 302
        assertFailure(AiSelectionFailure.INVALID_RESPONSE, catchThrowable { select(capsule) })
        assertThat(calls).hasValue(10)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `other selection refusal fails closed`(capsule: Boolean) {
        // given
        status = 422
        body = if (capsule) """{"code":"no_outfit"}""" else """{"code":"no_capsule"}"""

        // when
        val failure = catchThrowable { select(capsule) }

        // then
        assertFailure(AiSelectionFailure.INVALID_RESPONSE, failure)
        assertThat(calls).hasValue(1)
        assertThat(receivedPath).isEqualTo(if (capsule) "/api/capsule-selections" else "/api/outfit-selections")
        assertThat(breakers.circuitBreaker(if (capsule) CAPSULE_BREAKER_NAME else BREAKER_NAME)
            .metrics.numberOfFailedCalls).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `new response preserves explanation and rejects invalid or additional fields`(capsule: Boolean) {
        for (explanation in listOf("  Подходит для дождя.\nТёплый слой.  ", "я".repeat(2000))) {
            // given
            body = tools.jackson.databind.json.JsonMapper().writeValueAsString(
                mapOf("itemIds" to listOf(7), "explanation" to explanation),
            )

            // when
            val result = select(capsule)

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
            breakers.circuitBreaker(if (capsule) CAPSULE_BREAKER_NAME else BREAKER_NAME).reset()
            body = json

            // when
            val failure = catchThrowable { select(capsule) }

            // then
            assertFailure(AiSelectionFailure.INVALID_RESPONSE, failure)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `malformed success never coerces IDs or accepts empty duplicate or missing lists`(capsule: Boolean) {
        // given
        val invalid = listOf("{}", "null", "not-json", """{"itemIds":[]}""",
            """{"itemIds":[7.1]}""", """{"itemIds":["7"]}""", """{"itemIds":[null]}""",
            """{"itemIds":[7,7]}""", """{"itemIds":[0]}""", """{"itemIds":[7]} {}""")

        invalid.forEach {
            breakers.circuitBreaker(if (capsule) CAPSULE_BREAKER_NAME else BREAKER_NAME).reset()
            body = it

            // when
            val failure = catchThrowable { select(capsule) }

            // then
            assertFailure(AiSelectionFailure.INVALID_RESPONSE, failure)
        }
        assertThat(calls).hasValue(invalid.size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `domain refusal is ignored but technical errors open breaker without another request`(capsule: Boolean) {
        // given
        status = 422
        body = if (capsule) """{"code":"no_capsule"}""" else """{"code":"no_outfit"}"""
        val breaker = breakers.circuitBreaker(if (capsule) CAPSULE_BREAKER_NAME else BREAKER_NAME)

        // when
        val refusals = (1..3).map { catchThrowable { select(capsule) } }

        // then
        refusals.forEach { assertFailure(if (capsule) AiSelectionFailure.NO_CAPSULE else AiSelectionFailure.NO_OUTFIT, it) }
        assertThat(breaker.metrics.numberOfBufferedCalls).isZero()

        // given
        status = 503
        body = """{"code":"unavailable"}"""

        // when
        val failures = (1..3).map { catchThrowable { select(capsule) } }

        // then
        failures.forEach { assertFailure(AiSelectionFailure.UNAVAILABLE, it) }
        assertThat(calls).hasValue(5)
        assertThat(breaker.state.name).isEqualTo("OPEN")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `connection failure is not replayed by transport`(capsule: Boolean) {
        // given
        disconnect = true

        // when
        val failure = catchThrowable { select(capsule) }

        // then
        assertFailure(AiSelectionFailure.UNAVAILABLE, failure)
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `disabled hidden time limiter allows a delayed valid response`(capsule: Boolean) {
        // given
        val release = CountDownLatch(1)
        gate = release
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            // when
            val pending = executor.submit<List<Long>> { select(capsule).itemIds }
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
    fun `live policy updates both real breakers and keeps individual overrides and refusal predicates`() {
        // given
        val originalOutfit = breakers.circuitBreaker(BREAKER_NAME)
        val originalCapsule = breakers.circuitBreaker(CAPSULE_BREAKER_NAME)
        val prefix = "resilience4j.circuitbreaker"

        // when
        settings.apply(LiveClientSettingsTests.source(mapOf(
            "$prefix.configs.remote-service.sliding-window-size" to "4",
            "$prefix.configs.remote-service.minimum-number-of-calls" to "4",
            "$prefix.configs.remote-service.failure-rate-threshold" to "75",
            "$prefix.instances.$BREAKER_NAME.minimum-number-of-calls" to "3",
            "$prefix.instances.$CAPSULE_BREAKER_NAME.minimum-number-of-calls" to "2",
        )))
        val outfit = breakers.circuitBreaker(BREAKER_NAME)
        val capsule = breakers.circuitBreaker(CAPSULE_BREAKER_NAME)

        // then
        assertThat(outfit).isNotSameAs(originalOutfit)
        assertThat(capsule).isNotSameAs(originalCapsule)
        listOf(outfit, capsule).forEach {
            assertThat(it.circuitBreakerConfig.slidingWindowSize).isEqualTo(4)
            assertThat(it.circuitBreakerConfig.failureRateThreshold).isEqualTo(75f)
        }
        assertThat(outfit.circuitBreakerConfig.minimumNumberOfCalls).isEqualTo(3)
        assertThat(capsule.circuitBreakerConfig.minimumNumberOfCalls).isEqualTo(2)

        // given
        status = 422
        body = """{"code":"no_outfit"}"""

        // when
        val outfitRefusal = catchThrowable { client.select(selection) }
        body = """{"code":"no_capsule"}"""
        val capsuleRefusal = catchThrowable { client.selectCapsule(selection) }

        // then
        assertFailure(AiSelectionFailure.NO_OUTFIT, outfitRefusal)
        assertFailure(AiSelectionFailure.NO_CAPSULE, capsuleRefusal)
        assertThat(outfit.metrics.numberOfBufferedCalls).isZero()
        assertThat(capsule.metrics.numberOfBufferedCalls).isZero()

        // given
        status = 503
        body = """{"code":"unavailable"}"""

        // when
        val failures = (1..2).flatMap {
            listOf(catchThrowable { client.select(selection) }, catchThrowable { client.selectCapsule(selection) })
        }
        val rejected = catchThrowable { client.selectCapsule(selection) }

        // then
        (failures + rejected).forEach { assertFailure(AiSelectionFailure.UNAVAILABLE, it) }
        assertThat(outfit.state.name).isEqualTo("CLOSED")
        assertThat(outfit.metrics.numberOfFailedCalls).isEqualTo(2)
        assertThat(capsule.state.name).isEqualTo("OPEN")
        assertThat(calls).hasValue(6)
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
            AiSelectionService(
                users, items, photos, precipitation,
                mock(ru.itmo.clothesadvisor.repository.preferences.StylePreferencesRepository::class.java),
                content, client, transactions, meters, "user",
            ),
            outfits,
            idempotencyService(transactions),
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
            assertFailure(AiSelectionFailure.UNAVAILABLE, busy)
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fallback unwraps timeout transport and domain failures without details`(capsule: Boolean) {
        // given
        val request = Request.create(
            Request.HttpMethod.POST, "http://recommendation-service/api/outfit-selections",
            emptyMap(), null, Charsets.UTF_8, null,
        )
        val causes = listOf(
            java.net.SocketTimeoutException("secret") to AiSelectionFailure.TIMEOUT,
            AiSelectionException(AiSelectionFailure.NO_OUTFIT) to AiSelectionFailure.NO_OUTFIT,
            AiSelectionException(AiSelectionFailure.NO_CAPSULE) to AiSelectionFailure.NO_CAPSULE,
            feign.codec.DecodeException(200, "secret", request, java.io.IOException("secret")) to
                AiSelectionFailure.UNAVAILABLE,
        )

        causes.forEach { (cause, expected) ->
            val fallback = RecommendationFallbackFactory().create(java.util.concurrent.ExecutionException(cause))

            // when
            val error = catchThrowable {
                if (capsule) fallback.selectCapsule(selection) else fallback.select(selection)
            } as AiSelectionException

            // then
            assertThat(error.failure).isEqualTo(expected)
            assertThat(error.message).isNull()
            assertThat(error.cause).isNull()
        }
    }

    private fun select(capsule: Boolean) = if (capsule) client.selectCapsule(selection) else client.select(selection)

    private fun assertFailure(expected: AiSelectionFailure, error: Throwable?) {
        assertThat(error)
            .isInstanceOfSatisfying(AiSelectionException::class.java) {
                assertThat(it.failure).isEqualTo(expected)
                assertThat(it.message).isNull()
                assertThat(it.cause).isNull()
            }
    }

    companion object {
        private const val BREAKER_NAME = "RecommendationClientselectItemSelectionRequest"
        private const val CAPSULE_BREAKER_NAME = "RecommendationClientselectCapsuleItemSelectionRequest"
        private val calls = AtomicInteger()
        @Volatile private var status = 200
        @Volatile private var body = ""
        @Volatile private var received = ""
        @Volatile private var receivedPath = ""
        @Volatile private var receivedMethod = ""
        @Volatile private var disconnect = false
        @Volatile private var gate: CountDownLatch? = null
        @Volatile private var entered = CountDownLatch(1)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/api/") { exchange ->
                calls.incrementAndGet()
                receivedPath = exchange.requestURI.path
                receivedMethod = exchange.requestMethod
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
