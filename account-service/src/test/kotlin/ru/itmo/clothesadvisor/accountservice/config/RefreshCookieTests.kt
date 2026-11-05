package ru.itmo.clothesadvisor.accountservice.config

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RefreshCookieTests {
    @Test
    fun `explicit local HTTP setting changes only secure flag and expiry never slides`() {
        // given
        val now = Instant.parse("2026-11-05T10:00:00Z")
        val expiry = now.plus(Duration.ofDays(6))
        val secure = RefreshCookie(true, Clock.fixed(now, ZoneOffset.UTC))
        val local = RefreshCookie(false, Clock.fixed(now, ZoneOffset.UTC))

        // when
        val production = secure.create("opaque", expiry)
        val development = local.create("opaque", expiry)

        // then
        assertThat(production.isSecure).isTrue()
        assertThat(development.isSecure).isFalse()
        for (cookie in listOf(production, development)) {
            assertThat(cookie.isHttpOnly).isTrue()
            assertThat(cookie.domain).isNull()
            assertThat(cookie.path).isEqualTo("/api/auth")
            assertThat(cookie.sameSite).isEqualTo("Strict")
            assertThat(cookie.maxAge).isEqualTo(Duration.ofDays(6))
        }
        assertThat(local.clear().maxAge).isEqualTo(Duration.ZERO)
        assertThat(local.clear().isSecure).isFalse()
        assertThat(secure.clear().isSecure).isTrue()
        assertThat(secure.create("opaque", now.minusSeconds(1)).maxAge).isEqualTo(Duration.ZERO)
    }
}
