package ru.itmo.clothesadvisor.accountservice.service.auth

import java.util.HexFormat
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.config.PasswordConfiguration

class PasswordServiceTests {
    @Test
    fun `encoding uses random salt and the specified PBKDF2 parameters without trimming`() {

        // given
        val encoder = PasswordConfiguration().passwordEncoder()
        val service = PasswordService(encoder)
        val password = "  new password  "

        // when
        val result = service.encode(password).zipWith(service.encode(password))

        // then
        StepVerifier.create(result).assertNext { hashes ->
            assertThat(hashes.t1).startsWith("{pbkdf2-sha256-600000}")
            assertThat(hashes.t1).isNotEqualTo(hashes.t2)
            val bytes = HexFormat.of().parseHex(hashes.t1.substringAfter('}'))
            assertThat(bytes).hasSize(48)
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
                PBEKeySpec(password.toCharArray(), bytes.copyOfRange(0, 16), 600_000, 256),
            ).encoded
            assertThat(bytes.copyOfRange(16, 48)).isEqualTo(expected)
            assertThat(encoder.matches(password, hashes.t1)).isTrue()
            assertThat(encoder.matches("wrong password", hashes.t1)).isFalse()
            assertThat(encoder.matches(password.trim(), hashes.t1)).isFalse()
        }.verifyComplete()
    }

    @ParameterizedTest
    @ValueSource(ints = [12, 128])
    fun `new passwords accept both length boundaries`(length: Int) {

        // given
        val service = PasswordService(PasswordConfiguration().passwordEncoder())
        val password = "p".repeat(length)

        // when
        val result = service.encode(password).flatMap { service.matches(password, it) }

        // then
        StepVerifier.create(result).expectNext(true).verifyComplete()
    }

    @Test
    fun `invalid new passwords fail on subscription without invoking encoder or exposing input`() {

        // given
        val encoder = mock(PasswordEncoder::class.java)
        val service = PasswordService(encoder)

        for (password in listOf("", "shortsecret", " ".repeat(12), "s".repeat(129))) {

            // when
            val result = service.encode(password)

            // then
            StepVerifier.create(result).expectErrorSatisfies {
                assertThat(it).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(it.message).isEqualTo(
                    "Password must contain 12 to 128 characters and must not be blank",
                )
            }.verify()
        }
        verifyNoInteractions(encoder)
    }

    @Test
    fun `encode and matches are deferred and run away from the event loop`() {

        // given
        val encoder = mock(PasswordEncoder::class.java)
        val hash = "{pbkdf2-sha256-600000}" + "a".repeat(96)
        doAnswer {
            assertThat(Thread.currentThread().name).startsWith("boundedElastic-")
            assertThat(Schedulers.isInNonBlockingThread()).isFalse()
            hash
        }.`when`(encoder).encode(anyString())
        doAnswer {
            assertThat(Thread.currentThread().name).startsWith("boundedElastic-")
            assertThat(Schedulers.isInNonBlockingThread()).isFalse()
            true
        }.`when`(encoder).matches(anyString(), anyString())
        val service = PasswordService(encoder)

        // when
        val encoded = service.encode("new password")
        val matched = service.matches("new password", hash)

        // then
        verifyNoInteractions(encoder)
        StepVerifier.create(encoded.subscribeOn(Schedulers.parallel())).expectNext(hash).verifyComplete()
        StepVerifier.create(matched.subscribeOn(Schedulers.parallel())).expectNext(true).verifyComplete()
    }

    @Test
    fun `missing unsupported and malformed hashes all use one cached dummy hash and cannot match`() {

        // given
        val encoder = mock(PasswordEncoder::class.java)
        val dummyHash = "{pbkdf2-sha256-600000}" + "d".repeat(96)
        `when`(encoder.encode(anyString())).thenReturn(dummyHash)
        `when`(encoder.matches("new password", dummyHash)).thenReturn(true)
        val service = PasswordService(encoder)
        val hashes = listOf(null, "!", "new password", "{noop}new password", "{unknown}abc",
            "{pbkdf2-sha256-600000}abc", "{pbkdf2-sha256-600000}" + "z".repeat(96),
            "{pbkdf2-sha256-600000}" + "a".repeat(98))

        for (hash in hashes) {

            // when
            val result = service.matches("new password", hash)

            // then
            StepVerifier.create(result).expectNext(false).verifyComplete()
        }
        verify(encoder, times(1)).encode(anyString())
        verify(encoder, times(hashes.size)).matches("new password", dummyHash)
    }

    @Test
    fun `oversized authentication password fails without hashing`() {

        // given
        val encoder = mock(PasswordEncoder::class.java)
        val service = PasswordService(encoder)

        // when
        val result = service.matches("p".repeat(129), "!")

        // then
        StepVerifier.create(result).expectNext(false).verifyComplete()
        verifyNoInteractions(encoder)
    }

    @Test
    fun `encoder failures remain infrastructure errors`() {

        // given
        val encoder = mock(PasswordEncoder::class.java)
        val failure = IllegalStateException("Crypto provider unavailable")
        `when`(encoder.encode(anyString())).thenThrow(failure)
        val service = PasswordService(encoder)

        // when
        val result = service.matches("new password", null)

        // then
        StepVerifier.create(result).expectErrorMatches { it === failure }.verify()
    }
}
