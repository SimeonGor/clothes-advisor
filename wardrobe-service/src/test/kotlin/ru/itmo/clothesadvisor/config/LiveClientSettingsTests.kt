package ru.itmo.clothesadvisor.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.core.env.MapPropertySource
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.client.account.AccountClientFactory
import ru.itmo.clothesadvisor.client.account.AccountClientProperties
import ru.itmo.clothesadvisor.client.account.ReloadableAccountClient
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse

class LiveClientSettingsTests {
    private val original = mock(AccountClient::class.java)
    private val client = ReloadableAccountClient(original)
    private val factory = mock(AccountClientFactory::class.java)
    private val registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
        .ignoreExceptions(IllegalArgumentException::class.java).build())
    private val settings = LiveClientSettings(
        AccountClientProperties(), RecommendationClientProperties(), FileClientProperties(), client, factory, registry,
    )

    @Test
    fun `unchanged policy retains open state and metrics without rebuilding account`() {
        // given
        val breaker = registry.circuitBreaker(ACCOUNT_BREAKER)
        breaker.onError(1, TimeUnit.MILLISECONDS, IllegalStateException())
        breaker.transitionToOpenState()

        // when
        val changed = settings.apply(source(mapOf(
            "resilience4j.circuitbreaker.configs.remote-service.wait-duration-in-open-state" to "PT60.0005S",
        )))

        // then
        assertThat(changed).isFalse()
        assertThat(registry.circuitBreaker(ACCOUNT_BREAKER)).isSameAs(breaker)
        assertThat(breaker.state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThat(breaker.metrics.numberOfFailedCalls).isEqualTo(1)
        verifyNoInteractions(factory)
    }

    @Test
    fun `only changed breaker is replaced and bootstrap exception predicate survives`() {
        // given
        val originalBreakers = LiveClientSettings.BREAKER_NAMES.associateWith { registry.circuitBreaker(it) }
        originalBreakers.values.forEach { it.transitionToOpenState() }
        val replacements = AtomicInteger()
        registry.eventPublisher.onEntryReplaced { replacements.incrementAndGet() }

        // when
        val changed = settings.apply(source(mapOf("$INSTANCE_PREFIX.failure-rate-threshold" to "75")))

        // then
        assertThat(changed).isTrue()
        assertThat(replacements).hasValue(1)
        val replacement = registry.circuitBreaker(ACCOUNT_BREAKER)
        assertThat(replacement).isNotSameAs(originalBreakers.getValue(ACCOUNT_BREAKER))
        assertThat(replacement.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(replacement.circuitBreakerConfig.failureRateThreshold).isEqualTo(75f)
        assertThat(replacement.circuitBreakerConfig.ignoreExceptionPredicate.test(IllegalArgumentException())).isTrue()
        originalBreakers.filterKeys { it != ACCOUNT_BREAKER }.forEach { (name, breaker) ->
            assertThat(registry.circuitBreaker(name)).isSameAs(breaker)
            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.OPEN)
        }
        assertThat(settings.apply(source(mapOf("$INSTANCE_PREFIX.failure-rate-threshold" to "75")))).isFalse()
        assertThat(replacements).hasValue(1)
    }

    @ParameterizedTest
    @CsvSource(
        "app.account-client.connect-timeout,0s", "app.account-client.read-timeout,-1s",
        "app.account-client.read-timeout,2147483648ms", "app.account-client.read-timeout,not-a-duration",
        "app.account-client.read-timeout,''", "app.recommendation-client.read-timeout,''",
        "app.account-client.max-response-size,0B", "app.account-client.max-response-size,2147483648B",
        "app.recommendation-client.connect-timeout,0s", "app.recommendation-client.read-timeout,-1s",
        "app.recommendation-client.write-timeout,0s", "app.recommendation-client.write-timeout,-1s",
        "app.recommendation-client.write-timeout,2147483648ms",
        "app.recommendation-client.write-timeout,not-a-duration", "app.recommendation-client.write-timeout,''",
        "app.file-client.connect-timeout,0s", "app.file-client.read-timeout,-1s",
        "app.file-client.write-timeout,2147483648ms", "app.file-client.read-timeout,not-a-duration",
        "resilience4j.circuitbreaker.configs.remote-service.sliding-window-type,UNKNOWN",
        "resilience4j.circuitbreaker.configs.remote-service.sliding-window-size,0",
        "resilience4j.circuitbreaker.configs.remote-service.minimum-number-of-calls,0",
        "resilience4j.circuitbreaker.configs.remote-service.failure-rate-threshold,NaN",
        "resilience4j.circuitbreaker.configs.remote-service.failure-rate-threshold,101",
        "resilience4j.circuitbreaker.configs.remote-service.wait-duration-in-open-state,0s",
        "resilience4j.circuitbreaker.configs.remote-service.permitted-number-of-calls-in-half-open-state,0",
        "resilience4j.circuitbreaker.instances.RecommendationClientselectItemSelectionRequest.sliding-window-size,0",
        "resilience4j.circuitbreaker.instances.RecommendationClientselectItemSelectionRequest.wait-duration-in-open-state,''",
        "resilience4j.circuitbreaker.instances.RecommendationClientselectCapsuleItemSelectionRequest.sliding-window-size,0",
        "resilience4j.circuitbreaker.instances.RecommendationClientselectCapsuleItemSelectionRequest.wait-duration-in-open-state,''",
    )
    fun `invalid candidate changes neither clients nor breakers`(key: String, value: String) {
        // given
        val breaker = registry.circuitBreaker(ACCOUNT_BREAKER)
        breaker.transitionToOpenState()
        val candidate = source(mapOf("app.account-client.read-timeout" to "3s", key to value))

        // when
        val failure = catchThrowable { settings.apply(candidate) }

        // then
        assertThat(failure).isNotNull()
        assertThat(client.target).isSameAs(original)
        assertThat(settings.recommendationProperties()).isEqualTo(RecommendationClientProperties())
        assertThat(settings.fileProperties()).isEqualTo(FileClientProperties())
        assertThat(registry.circuitBreaker(ACCOUNT_BREAKER)).isSameAs(breaker)
        assertThat(breaker.state).isEqualTo(CircuitBreaker.State.OPEN)
        verifyNoInteractions(factory)
    }

    @Test
    fun `missing required keys retain working settings and allow valid recovery`() {
        // given
        settings.apply(source(mapOf(
            "app.recommendation-client.read-timeout" to "3s", "app.recommendation-client.write-timeout" to "2s",
        )))

        // when
        val failures = LiveClientSettings.REQUIRED_KEYS.map { missing ->
            catchThrowable { settings.apply(MapPropertySource("remote", remoteValues().filterKeys { it != missing })) }
        }
        val emptyFailure = catchThrowable { settings.apply(MapPropertySource("remote", emptyMap())) }

        // then
        assertThat(failures).allSatisfy { assertThat(it).isNotNull() }
        assertThat(emptyFailure).isNotNull()
        assertThat(settings.recommendationProperties().readTimeout).isEqualTo(Duration.ofSeconds(3))
        assertThat(settings.recommendationProperties().writeTimeout).isEqualTo(Duration.ofSeconds(2))
        assertThat(settings.apply(source())).isTrue()
        assertThat(settings.recommendationProperties()).isEqualTo(RecommendationClientProperties())
    }

    @Test
    fun `stable account bean selects target on subscription and captured recommendation settings stay immutable`() {
        // given
        val next = mock(AccountClient::class.java)
        val nextProperties = AccountClientProperties(readTimeout = Duration.ofSeconds(3))
        doReturn(next).`when`(factory).create(nextProperties)
        doReturn(Mono.empty<AccountUserResponse>()).`when`(next).getUser(7)
        val pending = client.getUser(7)
        val captured = settings.recommendationProperties()

        // when
        settings.apply(source(mapOf(
            "app.account-client.read-timeout" to "3s", "app.recommendation-client.read-timeout" to "4s",
            "app.recommendation-client.connect-timeout" to "1s", "app.recommendation-client.write-timeout" to "2s",
        )))
        pending.block(Duration.ofSeconds(1))

        // then
        assertThat(client.target).isSameAs(next)
        verifyNoInteractions(original)
        assertThat(captured).isEqualTo(RecommendationClientProperties())
        assertThat(captured.writeTimeout).isEqualTo(Duration.ofSeconds(30))
        assertThat(settings.recommendationProperties()).isEqualTo(RecommendationClientProperties(
            connectTimeout = Duration.ofSeconds(1), readTimeout = Duration.ofSeconds(4),
            writeTimeout = Duration.ofSeconds(2),
        ))
    }

    @Test
    fun `unsupported security and transport settings are not applied`() {
        // given
        val breaker = registry.circuitBreaker(ACCOUNT_BREAKER)

        // when
        val changed = settings.apply(source(mapOf(
            "$INSTANCE_PREFIX.ignore-exception-predicate" to "malicious.Class",
            "spring.datasource.url" to "jdbc:other", "spring.cloud.openfeign.client.default-config" to "other",
        )))

        // then
        assertThat(changed).isFalse()
        assertThat(registry.circuitBreaker(ACCOUNT_BREAKER)).isSameAs(breaker)
        verifyNoInteractions(factory)
    }

    companion object {
        const val ACCOUNT_BREAKER = "AccountClientgetUserlong"
        const val INSTANCE_PREFIX = "resilience4j.circuitbreaker.instances.$ACCOUNT_BREAKER"

        fun remoteValues(): Map<String, Any> = mapOf(
            "app.account-client.connect-timeout" to "2s",
            "app.account-client.read-timeout" to "5s",
            "app.account-client.max-response-size" to "1MB",
            "app.recommendation-client.connect-timeout" to "5s",
            "app.recommendation-client.read-timeout" to "90s",
            "app.recommendation-client.write-timeout" to "30s",
            "app.file-client.connect-timeout" to "5s",
            "app.file-client.write-timeout" to "30s",
            "app.file-client.read-timeout" to "90s",
            "resilience4j.circuitbreaker.configs.remote-service.sliding-window-type" to "COUNT_BASED",
            "resilience4j.circuitbreaker.configs.remote-service.sliding-window-size" to "100",
            "resilience4j.circuitbreaker.configs.remote-service.minimum-number-of-calls" to "100",
            "resilience4j.circuitbreaker.configs.remote-service.failure-rate-threshold" to "50",
            "resilience4j.circuitbreaker.configs.remote-service.wait-duration-in-open-state" to "60s",
            "resilience4j.circuitbreaker.configs.remote-service.permitted-number-of-calls-in-half-open-state" to "10",
        )

        fun source(overrides: Map<String, Any> = emptyMap()) = MapPropertySource("remote", remoteValues() + overrides)
    }
}
