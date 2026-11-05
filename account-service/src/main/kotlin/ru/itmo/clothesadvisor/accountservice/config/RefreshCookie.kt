package ru.itmo.clothesadvisor.accountservice.config

import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

@Component
internal class RefreshCookie(
    @param:Value("\${app.auth.refresh-cookie-secure:true}") private val secure: Boolean,
    private val clock: Clock,
) {
    fun create(token: String, expiresAt: Instant): ResponseCookie =
        cookie(token, Duration.ofSeconds(Duration.between(clock.instant(), expiresAt).seconds.coerceAtLeast(0)))

    fun clear(): ResponseCookie = cookie("", Duration.ZERO)

    private fun cookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(NAME, value).httpOnly(true).secure(secure)
            .sameSite("Strict").path("/api/auth").maxAge(maxAge).build()

    companion object {
        const val NAME = "ca_refresh"
    }
}
