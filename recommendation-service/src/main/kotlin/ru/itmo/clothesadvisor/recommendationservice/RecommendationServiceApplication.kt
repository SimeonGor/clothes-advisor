package ru.itmo.clothesadvisor.recommendationservice

import java.time.Clock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import ru.itmo.clothesadvisor.config.OpenAiConfiguration

@SpringBootApplication
@Import(OpenAiConfiguration::class)
class RecommendationServiceApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<RecommendationServiceApplication>(*args)
}
