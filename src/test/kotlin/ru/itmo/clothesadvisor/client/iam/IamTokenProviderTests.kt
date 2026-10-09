package ru.itmo.clothesadvisor.client.iam

import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class IamTokenProviderTests {
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private val now = AtomicReference(start)
    private val clock =
        mock(Clock::class.java).also { `when`(it.instant()).thenAnswer { now.get() } }
    private val client = mock(IamTokenClient::class.java)
    private val provider = IamTokenProvider(client, clock)

    @Test
    fun `token is cached until five minutes before its actual expiry`() {

        // given
        `when`(client.issueToken())
            .thenReturn(
                IamToken("first", start.plusSeconds(3600)),
                IamToken("second", start.plusSeconds(7200)),
            )

        // when
        val first = provider.accessToken()
        now.set(start.plusSeconds(3299))
        val cached = provider.accessToken()
        now.set(start.plusSeconds(3300))
        val renewed = provider.accessToken()

        // then
        assertThat(first).isEqualTo("first")
        assertThat(cached).isEqualTo("first")
        assertThat(renewed).isEqualTo("second")
        verify(client, times(2)).issueToken()
    }

    @Test
    fun `short lived token refreshes at half lifetime`() {

        // given
        `when`(client.issueToken())
            .thenReturn(
                IamToken("first", start.plusSeconds(120)),
                IamToken("second", start.plusSeconds(240)),
            )

        // when
        provider.accessToken()
        now.set(start.plusSeconds(59))
        val cached = provider.accessToken()
        now.set(start.plusSeconds(60))
        val renewed = provider.accessToken()

        // then
        assertThat(cached).isEqualTo("first")
        assertThat(renewed).isEqualTo("second")
        verify(client, times(2)).issueToken()
    }

    @Test
    fun `failed refresh uses valid token during cooldown but never returns expired token and recovers`() {

        // given
        `when`(client.issueToken())
            .thenReturn(IamToken("first", start.plusSeconds(40)))
            .thenThrow(IamTokenUnavailableException())
            .thenReturn(IamToken("recovered", start.plusSeconds(3600)))

        // when
        provider.accessToken()
        now.set(start.plusSeconds(20))
        val fallback = provider.accessToken()
        now.set(start.plusSeconds(39))
        val cooldown = provider.accessToken()
        now.set(start.plusSeconds(40))
        val expired = catchThrowable { provider.accessToken() }
        now.set(start.plusSeconds(49))
        val stillCoolingDown = catchThrowable { provider.accessToken() }
        now.set(start.plusSeconds(50))
        val recovered = provider.accessToken()

        // then
        assertThat(fallback).isEqualTo("first")
        assertThat(cooldown).isEqualTo("first")
        assertUnavailable(expired)
        assertUnavailable(stillCoolingDown)
        assertThat(recovered).isEqualTo("recovered")
        verify(client, times(3)).issueToken()
    }

    @Test
    fun `initial failure backs off for thirty seconds before retrying`() {

        // given
        `when`(client.issueToken())
            .thenThrow(IamTokenUnavailableException())
            .thenReturn(IamToken("short", start.plusSeconds(40)))

        // when
        val initialFailure = catchThrowable { provider.accessToken() }
        now.set(start.plusSeconds(29))
        val cooldownFailure = catchThrowable { provider.accessToken() }
        now.set(start.plusSeconds(30))
        val token = provider.accessToken()

        // then
        assertUnavailable(initialFailure)
        assertUnavailable(cooldownFailure)
        assertThat(token).isEqualTo("short")
        verify(client, times(2)).issueToken()
    }

    @Test
    fun `failed refresh cannot fall back to a token that expired during the request`() {

        // given
        `when`(client.issueToken())
            .thenReturn(IamToken("short", start.plusSeconds(40)))
            .thenAnswer {
                now.set(start.plusSeconds(40))
                throw IamTokenUnavailableException()
            }
        provider.accessToken()
        now.set(start.plusSeconds(20))

        // when
        val failure = catchThrowable { provider.accessToken() }

        // then
        assertUnavailable(failure)
        verify(client, times(2)).issueToken()
    }

    @Test
    fun `successful issuance is rejected if its token has expired by receipt`() {

        // given
        `when`(client.issueToken()).thenAnswer {
            now.set(start.plusSeconds(40))
            IamToken("expired-in-flight", start.plusSeconds(40))
        }

        // when
        val failure = catchThrowable { provider.accessToken() }

        // then
        assertUnavailable(failure)
        verify(client).issueToken()
    }

    @Test
    fun `concurrent callers share a single issuance`() {

        // given
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callersReady = CountDownLatch(6)
        val begin = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        `when`(client.issueToken()).thenAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            IamToken("shared", start.plusSeconds(3600))
        }
        try {

            // when
            val results =
                (1..6).map {
                    executor.submit<String> {
                        callersReady.countDown()
                        check(begin.await(5, TimeUnit.SECONDS))
                        provider.accessToken()
                    }
                }
            check(callersReady.await(5, TimeUnit.SECONDS))
            begin.countDown()
            check(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            val tokens = results.map { it.get(5, TimeUnit.SECONDS) }

            // then
            assertThat(tokens).containsOnly("shared").hasSize(6)
            verify(client).issueToken()
        } finally {
            release.countDown()
            begin.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted refresh preserves interruption and never falls back`() {

        // given
        `when`(client.issueToken())
            .thenReturn(IamToken("first", start.plusSeconds(3600)))
            .thenAnswer {
                Thread.currentThread().interrupt()
                throw IamTokenUnavailableException()
            }
        provider.accessToken()
        now.set(start.plusSeconds(3300))
        try {

            // when
            val failure = catchThrowable { provider.accessToken() }
            val interruptedCooldown = catchThrowable { provider.accessToken() }

            // then
            assertUnavailable(failure)
            assertUnavailable(interruptedCooldown)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            verify(client, times(2)).issueToken()
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertUnavailable(failure: Throwable?) {
        assertThat(failure).isInstanceOfSatisfying(IamTokenUnavailableException::class.java) {
            assertThat(it.message).isNull()
            assertThat(it.cause).isNull()
        }
    }
}
