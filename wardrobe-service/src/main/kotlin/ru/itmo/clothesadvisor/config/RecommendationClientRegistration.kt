package ru.itmo.clothesadvisor.config

import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.context.annotation.Configuration
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient

@Configuration
@EnableFeignClients(clients = [RecommendationClient::class])
class RecommendationClientRegistration
