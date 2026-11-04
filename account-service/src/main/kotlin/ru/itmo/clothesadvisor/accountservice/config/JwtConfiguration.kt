package ru.itmo.clothesadvisor.accountservice.config

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import java.security.Signature
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.security.converter.RsaKeyConverters
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder

@Configuration
internal class JwtConfiguration {
    @Bean fun jwtClock(): Clock = Clock.systemUTC()

    @Bean
    fun jwtKey(
        @Value("\${app.jwt.private-key}") privateKey: Resource,
        @Value("\${app.jwt.public-key}") publicKey: Resource,
    ): RSAKey {
        val private = privateKey.inputStream.use { RsaKeyConverters.pkcs8().convert(it) }
        val public = publicKey.inputStream.use { RsaKeyConverters.x509().convert(it) }
        require(public.modulus.bitLength() >= 2048 && private.modulus == public.modulus) {
            "JWT requires a matching RSA key pair of at least 2048 bits"
        }
        val proof = "account-jwt-key-check".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(private)
            update(proof)
            sign()
        }
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(public)
            update(proof)
            verify(signature)
        }) { "JWT requires a matching RSA key pair" }
        return RSAKey.Builder(public).privateKey(private).build()
    }

    @Bean
    fun jwtEncoder(key: RSAKey): JwtEncoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(key)))

    @Bean
    fun jwtDecoder(key: RSAKey, clock: Clock): ReactiveJwtDecoder =
        NimbusReactiveJwtDecoder.withPublicKey(key.toRSAPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256).build().apply {
                val claimsConverter = MappedJwtClaimSetConverter.withDefaults(emptyMap())
                setClaimSetConverter { claims ->
                    // The default converter derives iat from exp when iat is absent.
                    if (claims[JwtClaimNames.IAT] == null) {
                        throw BadJwtException("JWT must contain an issued-at claim")
                    }
                    claimsConverter.convert(claims)
                }
                val timestamps = JwtTimestampValidator(Duration.ofSeconds(30)).apply { setClock(clock) }
                setJwtValidator(DelegatingOAuth2TokenValidator(
                    timestamps, JwtIssuerValidator(ISSUER), JwtAudienceValidator(AUDIENCE),
                    RequiredJwtClaimsValidator(clock),
                ))
            }

    companion object {
        const val ISSUER = "urn:clothes-advisor:account"
        const val AUDIENCE = "clothes-advisor-api"
        const val TTL_SECONDS = 900L
    }
}

internal class RequiredJwtClaimsValidator(private val clock: Clock) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val subject = token.subject
        val issued = token.issuedAt
        val expires = token.expiresAt
        return if (subject != null && subject.matches(Regex("[0-9]+")) &&
            (subject.toLongOrNull() ?: 0) > 0 && issued != null && expires != null &&
            expires.isAfter(issued) && !issued.isAfter(clock.instant().plusSeconds(30))) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
        }
    }
}
