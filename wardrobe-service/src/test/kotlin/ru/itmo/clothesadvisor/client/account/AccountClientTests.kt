package ru.itmo.clothesadvisor.client.account

import com.sun.net.httpserver.HttpServer
import feign.Client
import feign.Request
import feign.Retryer
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.math.BigDecimal
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
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties
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
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.ExternalClientsConfiguration
import ru.itmo.clothesadvisor.controller.ApiExceptionHandler
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather
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
    internal class Probe(private val client: AccountClient) {
        @GetMapping("/account-probe/{method}")
        fun query(@PathVariable method: String): ResponseEntity<Void> {
            when (method) {
                "user" -> client.getUser(7)
                "stylist" -> client.requireStylistAccess(3, 7)
                "admin" -> client.requireAdminAccess(5, 7)
                else -> error("Unknown test method")
            }
            return ResponseEntity.noContent().build()
        }
    }

    @Autowired private lateinit var client: AccountClient
    @Autowired private lateinit var recommendation: RecommendationClient
    @Autowired private lateinit var factory: FeignClientFactory
    @Autowired private lateinit var breakers: CircuitBreakerRegistry
    @Autowired private lateinit var discovery: SimpleDiscoveryProperties
    @Autowired private lateinit var context: ApplicationContext
    @LocalServerPort private var port = 0

    @BeforeEach
    fun reset() {
        calls.set(0)
        status = 200
        body = VALID_USER
        disconnect = false
        gate = null
        entered = CountDownLatch(1)
        breakers.allCircuitBreakers.forEach { it.reset() }
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
            assertThat(breakers.circuitBreaker(breakerName(method)).metrics.numberOfSuccessfulCalls)
                .isEqualTo(1)
        }
        assertThat(calls).hasValue(3)
        assertThat(breakers.allCircuitBreakers.map { it.name }).containsExactlyInAnyOrder(
            "AccountClientgetUserlong", "AccountClientrequireStylistAccesslonglong",
            "AccountClientrequireAdminAccesslonglong", RECOMMENDATION_BREAKER,
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
        val options = factory.getInstance("account-service", Request.Options::class.java)!!
        val transport = factory.getInstance("account-service", Client::class.java)
        val recommendationOptions = factory.getInstance(
            "recommendation-service", Request.Options::class.java,
        )!!
        val selection = OutfitSelectionRequest(
            OutfitSelectionWeather(BigDecimal.ZERO, BigDecimal.ZERO, "NONE", "None"),
            listOf(OutfitSelectionCandidate(
                7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID",
            )),
        )

        // when
        val user = client.getUser(7)
        val result = recommendation.select(selection)

        // then
        assertThat(user.id).isEqualTo(7)
        assertThat(result.itemIds).containsExactly(7)
        assertThat(options.connectTimeoutMillis()).isEqualTo(2_000)
        assertThat(options.readTimeoutMillis()).isEqualTo(5_000)
        assertThat(options.isFollowRedirects).isFalse()
        assertThat(factory.getInstance("account-service", Retryer::class.java))
            .isSameAs(Retryer.NEVER_RETRY)
        assertThat(transport).isInstanceOf(FeignBlockingLoadBalancerClient::class.java)
        assertThat((transport as FeignBlockingLoadBalancerClient).delegate)
            .isInstanceOf(Client.Default::class.java)
        assertThat(recommendationOptions.connectTimeoutMillis()).isEqualTo(5_000)
        assertThat(recommendationOptions.readTimeoutMillis()).isEqualTo(90_000)
        assertThat(context.getBeansOfType(Request.Options::class.java)).isEmpty()
        assertThat(calls).hasValue(2)
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
        val instances = requireNotNull(discovery.instances.remove("account-service"))
        status = if (method == "user") 200 else 204

        try {
            // when
            val failure = catchThrowable { invoke(method) }

            // then
            assertUnavailable(failure)
            assertThat(calls).hasValue(0)
        } finally {
            discovery.instances["account-service"] = instances
        }

        // when
        val recovered = catchThrowable { invoke(method) }

        // then
        assertThat(recovered).isNull()
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "stylist", "admin"])
    fun `early connection close fails closed and reports JDK GET attempts`(
        method: String, reporter: TestReporter,
    ) {
        // given
        disconnect = true

        // when
        val failure = catchThrowable { invoke(method) }

        // then
        assertUnavailable(failure)
        assertThat(calls.get()).isPositive()
        reporter.publishEntry("$method EOF HTTP attempts", calls.get().toString())
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

    private fun publicResponse(method: String): HttpResponse<String> =
        HttpClient.newHttpClient().use {
            it.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/account-probe/$method"))
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
        private const val RECOMMENDATION_BREAKER = "RecommendationClientselectOutfitSelectionRequest"
        private val METHODS = listOf("user", "stylist", "admin")
        private val calls = AtomicInteger()
        @Volatile private var status = 200
        @Volatile private var body = VALID_USER
        @Volatile private var receivedPath = ""
        @Volatile private var receivedMethod = ""
        @Volatile private var receivedUserHeader: String? = null
        @Volatile private var receivedBody = ""
        @Volatile private var disconnect = false
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
                    receivedBody = it.requestBody.bufferedReader().readText()
                    if (disconnect) return@createContext
                    gate?.let { release ->
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    val recommendation = receivedPath == "/api/outfit-selections"
                    val responseStatus = if (recommendation) 200 else status
                    val bytes = if (recommendation) """{"itemIds":[7]}""".toByteArray()
                    else body.toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    if (responseStatus == 302) {
                        it.responseHeaders.add("Location", "/redirect-must-not-be-followed")
                    }
                    val noBody = responseStatus == 204 || bytes.isEmpty()
                    it.sendResponseHeaders(responseStatus, if (noBody) -1 else bytes.size.toLong())
                    if (!noBody) it.responseBody.write(bytes)
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
            executor.close()
        }
    }
}
