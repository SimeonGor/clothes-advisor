package ru.itmo.clothesadvisor.recommendationservice

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionProvider

@TestConfiguration(proxyBeanMethods = false)
class TransportTestConfiguration {
    @Bean("syncSelectionProvider")
    fun transport(client: OpenAiSelectionClient) = ItemSelectionProvider(client::select)
}
