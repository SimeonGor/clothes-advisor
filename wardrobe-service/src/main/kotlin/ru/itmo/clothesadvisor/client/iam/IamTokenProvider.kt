package ru.itmo.clothesadvisor.client.iam

import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class IamTokenProvider(private val client: IamTokenClient, private val clock: Clock) {
    private var cachedToken: IamToken? = null
    private var refreshAt = Instant.MIN
    private var retryAt = Instant.MIN

    @Synchronized
    fun accessToken(): String {
        if (Thread.currentThread().isInterrupted) throw IamTokenUnavailableException()

        val now = clock.instant()
        val current = cachedToken
        if (current != null && now.isBefore(refreshAt) && now.isBefore(current.expiresAt)) {
            return current.value
        }
        if (now.isBefore(retryAt)) return validCachedToken(now)

        return refreshToken()
    }

    private fun refreshToken(): String {
        try {
            val renewed = client.issueToken()
            val receivedAt = clock.instant()
            if (Thread.currentThread().isInterrupted || !receivedAt.isBefore(renewed.expiresAt)) {
                throw IamTokenUnavailableException()
            }
            val remainingLifetime = Duration.between(receivedAt, renewed.expiresAt)
            val refreshLeadTime =
                if (remainingLifetime < Duration.ofMinutes(10)) remainingLifetime.dividedBy(2)
                else Duration.ofMinutes(5)

            cachedToken = renewed
            refreshAt = renewed.expiresAt.minus(refreshLeadTime)
            retryAt = Instant.MIN
            return renewed.value
        } catch (_: IamTokenUnavailableException) {
            val failedAt = clock.instant()
            retryAt = failedAt.plusSeconds(30)

            if (Thread.currentThread().isInterrupted) throw IamTokenUnavailableException()
            return validCachedToken(failedAt)
        }
    }

    private fun validCachedToken(now: Instant): String =
        cachedToken?.takeIf { now.isBefore(it.expiresAt) }?.value
            ?: throw IamTokenUnavailableException()
}
