package ru.itmo.clothesadvisor.config

import java.time.Clock
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TimeConfiguration {
    @Bean
    fun clock(): Clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))
}
