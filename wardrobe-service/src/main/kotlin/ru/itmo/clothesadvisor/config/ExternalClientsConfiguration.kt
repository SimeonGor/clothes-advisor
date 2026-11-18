package ru.itmo.clothesadvisor.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import ru.itmo.clothesadvisor.client.account.AccountClientConfiguration
import ru.itmo.clothesadvisor.client.account.AccountClientFactory
import ru.itmo.clothesadvisor.client.account.AccountClientProperties
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.client.account.ReloadableAccountClient
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClientConfiguration

@Configuration
@Import(AccountClientConfiguration::class, BlockingAccountClient::class, LiveClientRefreshConfiguration::class,
    RecommendationClientConfiguration::class)
@EnableConfigurationProperties(RecommendationClientProperties::class, FileClientProperties::class)
class ExternalClientsConfiguration {
    @Bean
    internal fun liveClientSettings(
        account: AccountClientProperties,
        recommendation: RecommendationClientProperties,
        file: FileClientProperties,
        accountClient: ReloadableAccountClient,
        factory: AccountClientFactory,
        breakers: CircuitBreakerRegistry,
    ) = LiveClientSettings(account, recommendation, file, accountClient, factory, breakers)
}
