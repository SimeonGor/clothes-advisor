package ru.itmo.clothesadvisor.controller.auth

import jakarta.validation.Valid
import java.time.Clock
import java.time.temporal.ChronoUnit
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.security.auth.PasswordRules
import ru.itmo.clothesadvisor.config.SecurityConfiguration
import ru.itmo.clothesadvisor.dto.auth.AccessTokenResponse
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.dto.auth.LoginRequest

@RestController
@RequestMapping("/api/auth")
internal class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtEncoder: JwtEncoder,
    private val clock: Clock,
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): AccessTokenResponse {
        if (!PasswordRules.isWithinBcryptLimit(request.password)) throw InvalidLoginRequestException()
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(request.login, request.password),
        )
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val claims = JwtClaimsSet.builder()
            .issuer(SecurityConfiguration.ISSUER)
            .audience(listOf(SecurityConfiguration.AUDIENCE))
            .subject(authentication.name)
            .issuedAt(issuedAt)
            .notBefore(issuedAt)
            .expiresAt(issuedAt.plusSeconds(ACCESS_TOKEN_LIFETIME_SECONDS))
            .build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        return AccessTokenResponse(token.tokenValue, expiresIn = ACCESS_TOKEN_LIFETIME_SECONDS)
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: CurrentUser): CurrentUser = user

    private companion object {
        const val ACCESS_TOKEN_LIFETIME_SECONDS = 1800L
    }
}

internal class InvalidLoginRequestException : RuntimeException()
