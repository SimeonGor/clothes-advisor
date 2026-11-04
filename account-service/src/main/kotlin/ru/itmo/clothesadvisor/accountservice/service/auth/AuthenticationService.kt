package ru.itmo.clothesadvisor.accountservice.service.auth

import java.time.Clock
import org.springframework.dao.DataAccessException
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.config.AccountAuthenticationUnavailableException
import ru.itmo.clothesadvisor.accountservice.config.JwtConfiguration
import ru.itmo.clothesadvisor.accountservice.dto.auth.CurrentAccountResponse
import ru.itmo.clothesadvisor.accountservice.dto.auth.LoginResponse
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@Service
internal class AuthenticationService(
    private val credentials: CredentialAuthenticationService,
    private val encoder: JwtEncoder,
    private val clock: Clock,
    private val users: AppUserRepository,
) {
    fun login(login: String, password: String): Mono<LoginResponse> =
        credentials.authenticate(login, password)
            .onErrorMap(DataAccessException::class.java) { AccountAuthenticationUnavailableException() }
            .map { user ->
                val now = clock.instant()
                val claims = JwtClaimsSet.builder().issuer(JwtConfiguration.ISSUER)
                    .audience(listOf(JwtConfiguration.AUDIENCE)).subject(user.id.toString())
                    .issuedAt(now).expiresAt(now.plusSeconds(JwtConfiguration.TTL_SECONDS)).build()
                val token = encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims,
                )).tokenValue
                LoginResponse(token, JwtConfiguration.TTL_SECONDS)
            }

    fun currentAccount(userId: Long): Mono<CurrentAccountResponse> =
        users.findById(userId)
            .onErrorMap(DataAccessException::class.java) { AccountAuthenticationUnavailableException() }
            .switchIfEmpty(Mono.error(InvalidCredentialsException()))
            .filter { it.status == UserStatus.ACTIVE }
            .switchIfEmpty(Mono.error(AccessDeniedException()))
            .map { CurrentAccountResponse(it.id, it.login, it.role) }
}
