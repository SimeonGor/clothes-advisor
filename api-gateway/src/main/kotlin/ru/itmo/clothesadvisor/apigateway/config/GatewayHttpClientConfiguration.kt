package ru.itmo.clothesadvisor.apigateway.config

import org.springframework.cloud.gateway.config.HttpClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class GatewayHttpClientConfiguration {
    @Bean
    fun disableRetryOnConnectionReset(): HttpClientCustomizer = HttpClientCustomizer { httpClient ->
        httpClient.disableRetry(true)
    }
}
