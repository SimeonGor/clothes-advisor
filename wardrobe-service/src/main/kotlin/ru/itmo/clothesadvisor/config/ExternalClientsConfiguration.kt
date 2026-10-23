package ru.itmo.clothesadvisor.config

import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import ru.itmo.clothesadvisor.client.account.AccountClientConfiguration
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient

@Configuration
@EnableFeignClients(clients = [RecommendationClient::class])
@Import(AccountClientConfiguration::class, BlockingAccountClient::class)
class ExternalClientsConfiguration
