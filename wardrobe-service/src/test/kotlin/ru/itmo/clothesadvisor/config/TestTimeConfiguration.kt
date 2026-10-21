package ru.itmo.clothesadvisor.config

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class TestTimeConfiguration {
    @Bean @Primary fun fixedClock(): Clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC)

    companion object {
        val FIXED_TIME: Instant = Instant.parse("2026-01-01T10:00:00.123456Z")
    }
}
