package ru.itmo.clothesadvisor.client.account

import com.sun.net.httpserver.HttpServer
import feign.Client
import feign.Request
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.tracing.Tracer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
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
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryProperties
import org.springframework.cloud.client.discovery.simple.InstanceProperties
import org.springframework.cloud.openfeign.FeignClientFactory
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.ExternalClientsConfiguration
import ru.itmo.clothesadvisor.controller.ApiExceptionHandler
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionWeather
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

@SpringBootTest(
    classes = [AccountClientTests.ClientApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test", "account-client-test")
class AccountClientTests {
    @Configuration
    @Profile("account-client-test")
    @EnableAutoConfiguration
    @Import(ExternalClientsConfiguration::class, ApiExceptionHandler::class, Probe::class)
    class ClientApplication

    @RestController
    @Profile("account-client-test")
    internal class Probe(
        private val client: BlockingAccountClient,
        private val recommendation: RecommendationClient,
        private val tracer: Tracer,
    ) {
        val traces = CopyOnWriteArrayList<WorkerTrace>()

        @GetMapping("/account-probe/{method}")
        fun query(@PathVariable method: String): Mono<ResponseEntity<Void>> = Mono.fromCallable {
            val span = tracer.currentSpan()?.context()
            traces += WorkerTrace(
                span?.traceId(), span?.spanId(), MDC.get("traceId"), MDC.get("spanId"),
                Thread.currentThread().name,
            )
            when (method) {
                "user" -> client.getUser(7)
                "stylist" -> client.requireStylistAccess(3, 7)
                "admin" -> client.requireAdminAccess(5, 7)
                "recommendation" -> recommendation.select(ItemSelectionRequest(
                    ItemSelectionWeather(BigDecimal.ZERO, BigDecimal.ZERO, "NONE", "None"),
                    listOf(ItemSelectionCandidate(
                        7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID",
                    )),
                ))
                else -> error("Unknown test method")
            }
            ResponseEntity.noContent().build<Void>()
        }.subscribeOn(Schedulers.boundedElastic())
    }

    internal data class WorkerTrace(
        val traceId: String?,
        val spanId: String?,
        val mdcTraceId: String?,
        val mdcSpanId: String?,
        val thread: String,
    )

    @Autowired private lateinit var client: BlockingAccountClient
    @Autowired private lateinit var reactiveClient: AccountClient
    @Autowired private lateinit var properties: AccountClientProperties
    @Autowired private lateinit var recommendation: RecommendationClient
    @Autowired private lateinit var factory: FeignClientFactory
    @Autowired private lateinit var breakers: CircuitBreakerRegistry
    @Autowired private lateinit var discovery: SimpleReactiveDiscoveryProperties
    @Autowired private lateinit var context: ApplicationContext
    @Autowired private lateinit var probe: Probe
    @LocalServerPort private var port = 0

    @BeforeEach
    fun reset() {
        calls.set(0)
        status = 200
        body = VALID_USER
        disconnect = false
        gate = null
        splitBody = false
        responseContentType = "application/json"
        secondCalls.set(0)
        bodyStarted = CountDownLatch(1)
        entered = CountDownLatch(1)
        probe.traces.clear()
        receivedTraceparent = null
        breakers.allCircuitBreakers.forEach { it.reset() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "recommendation"])
    fun `HTTP worker and Feign breaker preserve trace and isolate the next request`(method: String) {
        // given
        val traceId = "0123456789abcdef0123456789abcdef"
        val parentSpanId = "0123456789abcdef"

        // when
        val correlated = publicResponse(method, "00-$traceId-$parentSpanId-01")
        val downstream = receivedTraceparent
        val independent = publicResponse(method)
        val nextDownstream = receivedTraceparent

        // then
        assertThat(correlated.statusCode()).isEqualTo(204)
        assertThat(independent.statusCode()).isEqualTo(204)
        assertThat(probe.traces).hasSize(2)
        val first = probe.traces[0]
        val second = probe.traces[1]
        assertThat(first.traceId).isEqualTo(traceId)
        assertThat(first.spanId).matches("[0-9a-f]{16}").isNotEqualTo(parentSpanId)
        assertThat(second.traceId).matches("[0-9a-f]{32}").isNotEqualTo(traceId)
        probe.traces.forEach {
            assertThat(it.mdcTraceId).isEqualTo(it.traceId)
            assertThat(it.mdcSpanId).isEqualTo(it.spanId)
            assertThat(it.thread).startsWith("boundedElastic-")
        }
        assertThat(downstream).matches("00-$traceId-[0-9a-f]{16}-01")
        assertThat(downstream!!.split('-')[2]).isNotEqualTo(first.spanId)
        assertThat(nextDownstream).matches("00-${second.traceId}-[0-9a-f]{16}-[0-9a-f]{2}")
        assertThat(nextDownstream!!.split('-')[2]).isNotEqualTo(second.spanId)
        val breaker = if (method == "user") breakerName(method) else RECOMMENDATION_BREAKER
        assertThat(breakers.circuitBreaker(breaker).metrics.numberOfSuccessfulCalls).isEqualTo(2)
        assertThat(calls).hasValue(2)
    }

    @Test
    fun `all three queries use explicit IDs and actual configured breaker names`() {
        // given
        val paths = listOf("/internal/users/7", "/internal/stylist-access/3/clients/7",
            "/internal/admin-access/5/users/7")

        METHODS.forEachIndexed { index, method ->
            status = if (method == "user") 200 else 204

            // when
            invoke(method)

            // then
            assertThat(receivedPath).isEqualTo(paths[index])
            assertThat(receivedMethod).isEqualTo("GET")
            assertThat(receivedUserHeader).isNull()
            assertThat(receivedBody).isEmpty()
            val breaker = breakers.circuitBreaker(breakerName(method))
            assertThat(breaker.metrics.numberOfSuccessfulCalls)
                .isEqualTo(1)
            val config = breaker.circuitBreakerConfig
            assertThat(config.slidingWindowSize).isEqualTo(2)
            assertThat(config.minimumNumberOfCalls).isEqualTo(2)
            assertThat(config.failureRateThreshold).isEqualTo(75f)
            assertThat(config.waitIntervalFunctionInOpenState.apply(1)).isEqualTo(30_000L)
            assertThat(config.permittedNumberOfCallsInHalfOpenState).isEqualTo(2)
        }
        assertThat(calls).hasValue(3)
        assertThat(breakers.allCircuitBreakers.map { it.name }).containsExactlyInAnyOrder(
            "AccountClientgetUserlong", "AccountClientrequireStylistAccesslonglong",
            "AccountClientrequireAdminAccesslonglong", RECOMMENDATION_BREAKER,
            "RecommendationClientselectCapsuleItemSelectionRequest",
        )
    }

    @Test
    fun `same client observes changed account state without caching or imposing a login limit`() {
        // given
        val initial = client.getUser(7)
        val longLogin = "u".repeat(120)
        body = """{"id":7,"login":"$longLogin","role":"STYLIST","status":"BLOCKED"}"""

        // when
        val changed = client.getUser(7)

        // then
        assertThat(initial.login).isEqualTo("user")
        assertThat(initial.role).isEqualTo(UserRole.USER)
        assertThat(initial.status).isEqualTo(UserStatus.ACTIVE)
        assertThat(changed.id).isEqualTo(7)
        assertThat(changed.login).isEqualTo(longLogin)
        assertThat(changed.role).isEqualTo(UserRole.STYLIST)
        assertThat(changed.status).isEqualTo(UserStatus.BLOCKED)
        assertThat(calls).hasValue(2)
    }

    @Test
    fun `account transport options and decoder remain isolated from recommendation`() {
        // given
        val transport = factory.getInstance("recommendation-service", Client::class.java)
        val recommendationOptions = factory.getInstance(
            "recommendation-service", Request.Options::class.java,
        )!!
        val selection = ItemSelectionRequest(
            ItemSelectionWeather(BigDecimal.ZERO, BigDecimal.ZERO, "NONE", "None"),
            listOf(ItemSelectionCandidate(
                7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID",
            )),
        )

        // when
        val user = client.getUser(7)
        val result = recommendation.select(selection)

        // then
        assertThat(user.id).isEqualTo(7)
        assertThat(result.itemIds).containsExactly(7)
        assertThat(properties.connectTimeout.toMillis()).isEqualTo(2_000)
        assertThat(properties.readTimeout.toMillis()).isEqualTo(5_000)
        assertThat(transport).isInstanceOf(FeignBlockingLoadBalancerClient::class.java)
        assertThat((transport as FeignBlockingLoadBalancerClient).delegate)
            .isInstanceOf(Client.Default::class.java)
        assertThat(recommendationOptions.connectTimeoutMillis()).isEqualTo(5_000)
        assertThat(recommendationOptions.readTimeoutMillis()).isEqualTo(90_000)
        assertThat(context.getBeansOfType(Request.Options::class.java)).isEmpty()
        assertThat(calls).hasValue(2)
    }

    @Test
    fun `reactive client is lazy and completes on a nonblocking thread after the whole body`() {
        // given
        splitBody = true
        val release = CountDownLatch(1)
        gate = release
        val result = reactiveClient.getUser(7)
        val completedOn = CopyOnWriteArrayList<Boolean>()
        assertThat(calls).hasValue(0)

        // when
        val pending = result.doOnNext { completedOn += Schedulers.isInNonBlockingThread() }
            .subscribeOn(Schedulers.parallel()).toFuture()
        try {
            assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(pending).isNotDone()
            assertThat(breakers.circuitBreaker(breakerName("user"))
                .metrics.numberOfBufferedCalls).isZero()
            release.countDown()
            val user = requireNotNull(pending.get(5, TimeUnit.SECONDS))

            // then
            assertThat(user.id).isEqualTo(7)
            assertThat(completedOn).containsExactly(true)
            assertThat(calls).hasValue(1)
            assertThat(breakers.circuitBreaker(breakerName("user"))
                .metrics.numberOfSuccessfulCalls).isEqualTo(1)
        } finally {
            release.countDown()
            pending.cancel(true)
        }
    }

    @Test
    fun `cancelling a split response releases the half open permit without recording success`() {
        // given
        splitBody = true
        val release = CountDownLatch(1)
        gate = release
        val breaker = breakers.circuitBreaker(breakerName("user"))
        breaker.transitionToOpenState()
        breaker.transitionToHalfOpenState()
        val cancelled = CountDownLatch(1)
        val pending = reactiveClient.getUser(7).doFinally { cancelled.countDown() }.toFuture()

        try {
            assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue()

            // when
            pending.cancel(true)
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue()
            release.countDown()
            gate = null
            splitBody = false
            client.getUser(7)
            client.getUser(7)

            // then
            assertThat(breaker.state.name).isEqualTo("CLOSED")
            assertThat(calls).hasValue(3)
        } finally {
            release.countDown()
            pending.cancel(true)
        }
    }

    @Test
    fun `same reactive client round robins two discovery instances`() {
        // given
        val instances = discovery.getInstances("account-service")
        val registered = instances.toList()
        instances.add(InstanceProperties().apply {
            uri = URI("http://127.0.0.1:${secondServer.address.port}")
            serviceId = "account-service"
            instanceId = "account-second"
        })

        try {
            // when
            val logins = (1..4).map { reactiveClient.getUser(7).block()!!.login }

            // then
            assertThat(logins).containsExactlyInAnyOrder("user", "user", "second", "second")
            assertThat(logins[0]).isEqualTo(logins[2]).isNotEqualTo(logins[1])
            assertThat(calls).hasValue(2)
            assertThat(secondCalls).hasValue(2)
        } finally {
            instances.clear()
            instances.addAll(registered)
        }
    }

    @Test
    fun `valid user JSON is decoded even with an unrelated content type`() {
        // given
        responseContentType = "text/plain"

        // when
        val user = client.getUser(7)

        // then
        assertThat(user.id).isEqualTo(7)
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [1_048_576, 1_048_577])
    fun `account response size limit accepts its boundary and rejects larger bodies`(responseSize: Int) {
        // given
        body = VALID_USER + " ".repeat(responseSize - VALID_USER.length)

        // when
        val response = publicResponse("user")

        // then
        val expectedStatus = if (responseSize == 1_048_576) 204 else 503
        assertThat(response.statusCode()).isEqualTo(expectedStatus)
        assertThat(calls).hasValue(1)
        assertThat(response.body()).doesNotContain("maxInMemorySize", "DataBufferLimitException")
    }

    @Test
    fun `user success rejects malformed missing extra duplicate coerced and mismatched fields`() {
        // given
        val invalid = listOf(
            "", "null", "[]", "not-json", "{}", "$VALID_USER {}",
            VALID_USER.replace("\"id\":7", "\"id\":7,\"id\":7"),
            VALID_USER.replace("\"id\":7,", ""),
            VALID_USER.replace("\"login\":\"user\",", ""),
            VALID_USER.replace("\"role\":\"USER\",", ""),
            VALID_USER.replace(",\"status\":\"ACTIVE\"", ""),
            VALID_USER.replace("}", ",\"password_hash\":\"private\"}"),
            *listOf("0", "-1", "8", "7.0", "\"7\"", "null", "9223372036854775808")
                .map { VALID_USER.replace("\"id\":7", "\"id\":$it") }.toTypedArray(),
            *listOf("\"\"", "\"  \"", "7", "null", "true")
                .map { VALID_USER.replace("\"user\"", it) }.toTypedArray(),
            *listOf("\"user\"", "\"UNKNOWN\"", "7", "null")
                .map { VALID_USER.replace("\"USER\"", it) }.toTypedArray(),
            *listOf("\"active\"", "\"UNKNOWN\"", "7", "null")
                .map { VALID_USER.replace("\"ACTIVE\"", it) }.toTypedArray(),
        )

        invalid.forEach { payload ->
            body = payload
            breakers.circuitBreaker(breakerName("user")).reset()

            // when
            val failure = catchThrowable { client.getUser(7) }

            // then
            assertUnavailable(failure)
        }
        assertThat(calls).hasValue(invalid.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `wrong successful HTTP statuses never return a user or grant access`(method: String) {
        // given
        val invalidStatuses = if (method == "user") listOf(201, 202, 204, 206)
        else listOf(200, 201, 202, 206)

        invalidStatuses.forEach { code ->
            status = code
            breakers.circuitBreaker(breakerName(method)).reset()

            // when
            val failure = catchThrowable { invoke(method) }

            // then
            assertUnavailable(failure)
        }
        assertThat(calls).hasValue(invalidStatuses.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `empty not found is ignored but technical errors open breaker and recovery is fresh`(
        method: String,
    ) {
        // given
        status = 404
        body = ""
        val breaker = breakers.circuitBreaker(breakerName(method))

        // when
        val denials = (1..3).map { catchThrowable { invoke(method) } }

        // then
        denials.forEach { assertThat(it).isInstanceOf(EntityNotFoundException::class.java) }
        assertThat(breaker.metrics.numberOfBufferedCalls).isZero()

        // given
        status = 503
        body = "private upstream failure"

        // when
        val failures = (1..3).map { catchThrowable { invoke(method) } }

        // then
        failures.forEach { assertUnavailable(it) }
        assertThat(calls).hasValue(5)
        assertThat(breaker.state.name).isEqualTo("OPEN")

        // given
        breaker.transitionToHalfOpenState()
        status = if (method == "user") 200 else 204
        body = VALID_USER

        // when
        val recovered = catchThrowable { invoke(method) }

        // then
        assertThat(recovered).isNull()
        assertThat(calls).hasValue(6)
    }

    @Test
    fun `only exact admin forbidden is a domain denial and is ignored by breaker`() {
        // given
        status = 403
        body = """{"code":"forbidden"}"""
        val breaker = breakers.circuitBreaker(breakerName("admin"))

        // when
        val denials = (1..3).map { catchThrowable { client.requireAdminAccess(5, 7) } }
        val userFailure = catchThrowable { client.getUser(7) }
        val stylistFailure = catchThrowable { client.requireStylistAccess(3, 7) }

        // then
        denials.forEach {
            assertThat(it).isInstanceOf(AccessDeniedException::class.java)
            assertThat(it!!.cause).isNull()
        }
        assertThat(breaker.metrics.numberOfBufferedCalls).isZero()
        assertUnavailable(userFailure)
        assertUnavailable(stylistFailure)
        assertThat(calls).hasValue(5)
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `unexpected statuses and malformed denials fail closed without retry`(method: String) {
        // given
        val invalid = listOf(
            400 to """{"code":"invalid_request"}""", 401 to "", 429 to "", 500 to "",
            502 to "private upstream body", 503 to "", 504 to "", 302 to "",
            404 to " ", 404 to "{}", 404 to """{"code":"not_found"}""",
            403 to "", 403 to "{}", 403 to "null", 403 to "not-json",
            403 to """{"code":"FORBIDDEN"}""", 403 to """{"code":403}""",
            403 to """{"code":"forbidden","detail":"private"}""",
            403 to """{"code":"forbidden","code":"forbidden"}""",
            403 to """{"code":"forbidden"} {}""",
        )

        invalid.forEach { (code, payload) ->
            status = code
            body = payload
            breakers.circuitBreaker(breakerName(method)).reset()

            // when
            val failure = catchThrowable { invoke(method) }

            // then
            assertUnavailable(failure)
        }
        assertThat(calls).hasValue(invalid.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `empty discovery fails without HTTP and restoring an instance recovers`(method: String) {
        // given
        val instances = discovery.getInstances("account-service")
        val registered = instances.toList()
        instances.clear()
        status = if (method == "user") 200 else 204

        try {
            // when
            val failure = catchThrowable { invoke(method) }

            // then
            assertUnavailable(failure)
            assertThat(calls).hasValue(0)
        } finally {
            instances.addAll(registered)
        }

        // when
        val recovered = catchThrowable { invoke(method) }

        // then
        assertThat(recovered).isNull()
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `early connection close fails closed with exactly one HTTP attempt`(method: String) {
        // given
        disconnect = true

        // when
        val failure = catchThrowable { invoke(method) }

        // then
        assertUnavailable(failure)
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `disabled extra time limiter allows a delayed valid response`(method: String) {
        // given
        status = if (method == "user") 200 else 204
        val release = CountDownLatch(1)
        gate = release
        val caller = Executors.newVirtualThreadPerTaskExecutor()

        try {
            // when
            val pending = caller.submit<Any?> { invoke(method) }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(catchThrowable { pending.get(30, TimeUnit.MILLISECONDS) })
                .isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            release.countDown()

            // then
            assertThat(catchThrowable { pending.get(5, TimeUnit.SECONDS) }).isNull()
            assertThat(calls).hasValue(1)
        } finally {
            release.countDown()
            caller.close()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `public probe preserves not found and sanitizes technical and open breaker responses`(
        method: String,
    ) {
        // given
        status = 404
        body = ""

        // when
        val missing = publicResponse(method)

        // then
        assertThat(missing.statusCode()).isEqualTo(404)
        assertThat(missing.body()).isEmpty()

        // given
        status = 500
        body = "private credential-shaped test detail"

        // when
        val unavailable = (1..3).map { publicResponse(method) }

        // then
        unavailable.forEach {
            assertThat(it.statusCode()).isEqualTo(503)
            assertThat(it.body()).isEmpty()
        }
        assertThat(calls).hasValue(3)
    }

    @Test
    fun `public probe retains existing admin forbidden representation`() {
        // given
        status = 403
        body = """{"code":"forbidden"}"""

        // when
        val denied = publicResponse("admin")

        // then
        assertThat(denied.statusCode()).isEqualTo(403)
        assertThat(denied.body()).isEqualTo("""{"error":"forbidden"}""")
    }

    private fun publicResponse(method: String, traceparent: String? = null): HttpResponse<String> =
        HttpClient.newHttpClient().use {
            it.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/account-probe/$method"))
                    .apply { traceparent?.let { header("traceparent", it) } }
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

    private fun invoke(method: String): Any = when (method) {
        "user" -> client.getUser(7)
        "stylist" -> client.requireStylistAccess(3, 7)
        "admin" -> client.requireAdminAccess(5, 7)
        else -> error("Unknown test method")
    }

    private fun breakerName(method: String) = when (method) {
        "user" -> "AccountClientgetUserlong"
        "stylist" -> "AccountClientrequireStylistAccesslonglong"
        "admin" -> "AccountClientrequireAdminAccesslonglong"
        else -> error("Unknown test method")
    }

    private fun assertUnavailable(failure: Throwable?) {
        assertThat(failure).isExactlyInstanceOf(AccountUnavailableException::class.java)
        assertThat(failure!!.message).isNull()
        assertThat(failure.cause).isNull()
    }

    companion object {
        private const val VALID_USER =
            """{"id":7,"login":"user","role":"USER","status":"ACTIVE"}"""
        private const val RECOMMENDATION_BREAKER = "RecommendationClientselectItemSelectionRequest"
        private val METHODS = listOf("user", "stylist", "admin")
        private val calls = AtomicInteger()
        private val secondCalls = AtomicInteger()
        @Volatile private var status = 200
        @Volatile private var body = VALID_USER
        @Volatile private var receivedPath = ""
        @Volatile private var receivedMethod = ""
        @Volatile private var receivedUserHeader: String? = null
        @Volatile private var receivedBody = ""
        @Volatile private var receivedTraceparent: String? = null
        @Volatile private var disconnect = false
        @Volatile private var splitBody = false
        @Volatile private var responseContentType = "application/json"
        @Volatile private var bodyStarted = CountDownLatch(1)
        @Volatile private var gate: CountDownLatch? = null
        @Volatile private var entered = CountDownLatch(1)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/") { exchange ->
                exchange.use {
                    calls.incrementAndGet()
                    receivedPath = it.requestURI.path
                    receivedMethod = it.requestMethod
                    receivedUserHeader = it.requestHeaders.getFirst("X-User-Id")
                    receivedTraceparent = it.requestHeaders.getFirst("traceparent")
                    receivedBody = it.requestBody.bufferedReader().readText()
                    if (disconnect) return@createContext
                    val responseGate = gate
                    val split = splitBody
                    responseGate?.takeUnless { split }?.let { release ->
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    val recommendation = receivedPath == "/api/outfit-selections"
                    val responseStatus = if (recommendation) 200 else status
                    val bytes = if (recommendation) """{"itemIds":[7]}""".toByteArray()
                    else body.toByteArray()
                    it.responseHeaders.add("Content-Type", responseContentType)
                    if (responseStatus == 302) {
                        it.responseHeaders.add("Location", "/redirect-must-not-be-followed")
                    }
                    val noBody = responseStatus == 204 || bytes.isEmpty()
                    it.sendResponseHeaders(responseStatus, if (noBody) -1 else bytes.size.toLong())
                    if (!noBody) {
                        if (split) {
                            val midpoint = bytes.size / 2
                            it.responseBody.write(bytes, 0, midpoint)
                            it.responseBody.flush()
                            bodyStarted.countDown()
                            responseGate?.await(5, TimeUnit.SECONDS)
                            it.responseBody.write(bytes, midpoint, bytes.size - midpoint)
                        } else {
                            it.responseBody.write(bytes)
                        }
                    }
                }
            }
            start()
        }

        private val secondServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/internal/users/7") { exchange ->
                exchange.use {
                    secondCalls.incrementAndGet()
                    val bytes = VALID_USER.replace("user", "second").toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun discovery(registry: DynamicPropertyRegistry) {
            listOf("account-service", "recommendation-service").forEach { service ->
                registry.add("spring.cloud.discovery.client.simple.instances.$service[0].uri") {
                    "http://127.0.0.1:${server.address.port}"
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun close() {
            gate?.countDown()
            server.stop(0)
            secondServer.stop(0)
            executor.close()
        }
    }
}
