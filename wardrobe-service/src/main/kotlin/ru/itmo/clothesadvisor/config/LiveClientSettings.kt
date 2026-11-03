package ru.itmo.clothesadvisor.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.PropertySource
import ru.itmo.clothesadvisor.client.account.AccountClientFactory
import ru.itmo.clothesadvisor.client.account.AccountClientProperties
import ru.itmo.clothesadvisor.client.account.ReloadableAccountClient

@ConfigurationProperties("app.recommendation-client")
internal data class RecommendationClientProperties(
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(90),
    val writeTimeout: Duration = Duration.ofSeconds(30),
)

internal data class ClientBreakerPolicy(
    val slidingWindowType: CircuitBreakerConfig.SlidingWindowType,
    val slidingWindowSize: Int,
    val minimumNumberOfCalls: Int,
    val failureRateThreshold: Float,
    val waitDurationInOpenState: Duration,
    val permittedNumberOfCallsInHalfOpenState: Int,
) {
    fun configure(base: CircuitBreakerConfig): CircuitBreakerConfig {
        require(failureRateThreshold.isFinite())
        return CircuitBreakerConfig.from(base)
            .slidingWindowType(slidingWindowType)
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(waitDurationInOpenState)
            .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
            .build()
    }

    companion object {
        fun from(config: CircuitBreakerConfig) = ClientBreakerPolicy(
            config.slidingWindowType, config.slidingWindowSize, config.minimumNumberOfCalls,
            config.failureRateThreshold,
            Duration.ofMillis(config.waitIntervalFunctionInOpenState.apply(1)),
            config.permittedNumberOfCallsInHalfOpenState,
        )
    }
}

internal data class ClientBreakerOverrides(
    val slidingWindowType: CircuitBreakerConfig.SlidingWindowType? = null,
    val slidingWindowSize: Int? = null,
    val minimumNumberOfCalls: Int? = null,
    val failureRateThreshold: Float? = null,
    val waitDurationInOpenState: Duration? = null,
    val permittedNumberOfCallsInHalfOpenState: Int? = null,
) {
    fun applyTo(common: ClientBreakerPolicy) = ClientBreakerPolicy(
        slidingWindowType ?: common.slidingWindowType,
        slidingWindowSize ?: common.slidingWindowSize,
        minimumNumberOfCalls ?: common.minimumNumberOfCalls,
        failureRateThreshold ?: common.failureRateThreshold,
        waitDurationInOpenState ?: common.waitDurationInOpenState,
        permittedNumberOfCallsInHalfOpenState ?: common.permittedNumberOfCallsInHalfOpenState,
    )
}

// Only immutable transport targets/options and the six listed breaker knobs are published.
internal class LiveClientSettings(
    initialAccount: AccountClientProperties,
    initialRecommendation: RecommendationClientProperties,
    private val accountClient: ReloadableAccountClient,
    private val accountFactory: AccountClientFactory,
    private val breakers: CircuitBreakerRegistry,
) {
    private var account = initialAccount
    @Volatile private var recommendation = initialRecommendation
    private val bootstrapBreakers = BREAKER_NAMES.associateWith { breakers.circuitBreaker(it).circuitBreakerConfig }
    private var policies = bootstrapBreakers.mapValues { ClientBreakerPolicy.from(it.value) }

    init {
        validate(initialAccount, initialRecommendation)
    }

    fun recommendationProperties() = recommendation

    @Synchronized
    fun apply(source: PropertySource<*>): Boolean {
        val binder = Binder(ConfigurationPropertySources.from(source).filterNotNull())
        // Missing remote keys must not silently restore defaults after a server/repository mistake.
        REQUIRED_KEYS.forEach { require(!source.getProperty(it)?.toString().isNullOrBlank()) }
        BREAKER_NAMES.flatMap { name -> POLICY_KEYS.map { "$BREAKER_PREFIX.instances.$name.$it" } }
            .filter { source.containsProperty(it) }
            .forEach { require(!source.getProperty(it)?.toString().isNullOrBlank()) }
        val nextAccount = binder.bind("app.account-client", AccountClientProperties::class.java).get()
        val nextRecommendation = binder.bind(
            "app.recommendation-client", RecommendationClientProperties::class.java,
        ).get()
        validate(nextAccount, nextRecommendation)
        val common = binder.bind("$BREAKER_PREFIX.configs.remote-service", ClientBreakerPolicy::class.java).get()
        common.configure(CircuitBreakerConfig.ofDefaults())
        val overrides = binder.bind(
            "$BREAKER_PREFIX.instances", Bindable.mapOf(String::class.java, ClientBreakerOverrides::class.java),
        ).orElse(null).orEmpty()
        val configs = BREAKER_NAMES.associateWith { name ->
            val policy = overrides[name]?.applyTo(common) ?: common
            policy.configure(bootstrapBreakers.getValue(name))
        }
        val nextPolicies = configs.mapValues { ClientBreakerPolicy.from(it.value) }
        val changedPolicies = nextPolicies.filter { (name, policy) -> policy != policies.getValue(name) }
        val replacements = changedPolicies.mapValues { (name, _) ->
            CircuitBreaker.of(name, configs.getValue(name), breakers.circuitBreaker(name).tags)
        }
        val nextTarget = if (nextAccount != account) accountFactory.create(nextAccount) else null
        val changed = nextTarget != null || nextRecommendation != recommendation || replacements.isNotEmpty()

        // All binding, validation and target construction succeeded before any working state changes.
        replacements.forEach { (name, breaker) -> breakers.replace(name, breaker) }
        if (nextTarget != null) accountClient.target = nextTarget
        account = nextAccount
        recommendation = nextRecommendation
        policies = nextPolicies
        return changed
    }

    private fun validate(account: AccountClientProperties, recommendation: RecommendationClientProperties) {
        listOf(account.connectTimeout, account.readTimeout, recommendation.connectTimeout,
            recommendation.readTimeout, recommendation.writeTimeout)
            .forEach { require(it.toMillis() in 1..Int.MAX_VALUE.toLong()) }
        require(account.maxResponseSize.toBytes() in 1..Int.MAX_VALUE.toLong())
    }

    companion object {
        const val BREAKER_PREFIX = "resilience4j.circuitbreaker"
        val BREAKER_NAMES = listOf(
            "AccountClientgetUserlong", "AccountClientrequireStylistAccesslonglong",
            "AccountClientrequireAdminAccesslonglong", "RecommendationClientselectItemSelectionRequest",
            "RecommendationClientselectCapsuleItemSelectionRequest",
        )
        private val POLICY_KEYS = listOf(
            "sliding-window-type", "sliding-window-size", "minimum-number-of-calls", "failure-rate-threshold",
            "wait-duration-in-open-state", "permitted-number-of-calls-in-half-open-state",
        )
        val REQUIRED_KEYS = listOf(
            "app.account-client.connect-timeout", "app.account-client.read-timeout", "app.account-client.max-response-size",
            "app.recommendation-client.connect-timeout", "app.recommendation-client.read-timeout",
            "app.recommendation-client.write-timeout",
        ) + POLICY_KEYS.map { "$BREAKER_PREFIX.configs.remote-service.$it" }
    }
}
