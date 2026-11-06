package ru.itmo.clothesadvisor.config

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TimeConfiguration {
    @Bean fun clock(): Clock = Clock.systemUTC()
}
