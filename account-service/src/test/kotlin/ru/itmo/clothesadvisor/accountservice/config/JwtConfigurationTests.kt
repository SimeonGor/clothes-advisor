package ru.itmo.clothesadvisor.accountservice.config

import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.security.oauth2.jwt.Jwt

class JwtConfigurationTests {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val validator = RequiredJwtClaimsValidator(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `required claims permit clock skew boundary and reject absent or inconsistent times`() {
        // given
        val valid = jwt(now.plusSeconds(30), now.plusSeconds(900))
        val invalid = listOf(jwt(now.plusSeconds(31), now.plusSeconds(900)),
            jwt(null, now.plusSeconds(900)), jwt(now, null))

        // when
        val accepted = validator.validate(valid)
        val rejected = invalid.map(validator::validate)

        // then
        assertThat(accepted.hasErrors()).isFalse()
        assertThat(rejected).allMatch { it.hasErrors() }
    }

    @Test
    fun `startup loads matching PEM resources and rejects mismatched or undersized keys`() {
        // given
        val loader = DefaultResourceLoader()
        val configuration = JwtConfiguration()
        val private = loader.getResource(TestJwt.privateKeyLocation)
        val public = loader.getResource(TestJwt.publicKeyLocation)
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val small = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

        // when
        val key = configuration.jwtKey(private, public)

        // then
        assertThat(key.toRSAPublicKey()).isEqualTo(TestJwt.keyPair.public)
        assertThatThrownBy { configuration.jwtKey(private, pem("PUBLIC KEY", other.public.encoded)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { configuration.jwtKey(pem("PRIVATE KEY", small.private.encoded),
            pem("PUBLIC KEY", small.public.encoded)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { configuration.jwtKey(ByteArrayResource("invalid".toByteArray()), public) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun jwt(issued: Instant?, expires: Instant?): Jwt = Jwt.withTokenValue("test")
        .header("alg", "RS256").subject("1").apply {
            if (issued != null) issuedAt(issued)
            if (expires != null) expiresAt(expires)
        }.build()

    private fun pem(label: String, bytes: ByteArray) = ByteArrayResource(
        ("-----BEGIN $label-----\n" + Base64.getEncoder().encodeToString(bytes) +
            "\n-----END $label-----").toByteArray(),
    )
}
