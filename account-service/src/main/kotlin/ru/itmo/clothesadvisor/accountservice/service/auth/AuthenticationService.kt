package ru.itmo.clothesadvisor.accountservice.service.auth

import java.time.Clock
import java.time.Instant
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import org.springframework.dao.DataAccessException
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionException
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.config.AccountAuthenticationUnavailableException
import ru.itmo.clothesadvisor.accountservice.config.AuthenticationProperties
import ru.itmo.clothesadvisor.accountservice.config.JwtConfiguration
import ru.itmo.clothesadvisor.accountservice.dto.auth.CurrentAccountResponse
import ru.itmo.clothesadvisor.accountservice.dto.auth.LoginResponse
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.accountservice.repository.auth.AuthSessionRepository

@Service
internal class AuthenticationService(
    private val credentials: CredentialAuthenticationService,
    private val encoder: JwtEncoder,
    private val clock: Clock,
    private val users: AppUserRepository,
    private val sessions: AuthSessionRepository,
    private val properties: AuthenticationProperties,
    transactionManager: ReactiveTransactionManager,
) {
    private val transactions = TransactionalOperator.create(transactionManager)
    private val random = SecureRandom()

    fun login(login: String, password: String): Mono<SessionAuthentication> =
        credentials.authenticate(login, password)
            .flatMap { verified ->
                transactions.execute {
                    users.findByIdForUpdate(verified.id)
                        .filter { it.status == UserStatus.ACTIVE && it.passwordHash == verified.passwordHash }
                        .flatMap { user ->
                            sessions.create(UUID.randomUUID(), user.id, clock.instant().plus(properties.refreshTokenTtl))
                                .flatMap { session -> issue(session.id, user.id, session.expiresAt) }
                        }
                }.singleOrEmpty()
            }
            .switchIfEmpty(Mono.error(InvalidCredentialsException()))
            .onErrorMap(::databaseFailure) { AccountAuthenticationUnavailableException() }

    fun refresh(token: String?): Mono<SessionAuthentication> = Mono.defer {
        tokenHash(token).flatMap { hash ->
            sessions.findToken(hash).flatMap { initial ->
                transactions.execute {
                    // The user lock is also the password-reset boundary. Never lock a session first.
                    users.findByIdForUpdate(initial.userId).flatMap { user ->
                        sessions.findToken(hash).flatMap { current ->
                            if (current.usedAt != null) {
                                // Complete the transaction normally, then reject outside it so revocation commits.
                                sessions.revoke(current.sessionId).then(Mono.empty<SessionAuthentication>())
                            } else if (current.revokedAt != null || !current.expiresAt.isAfter(clock.instant()) ||
                                user.status != UserStatus.ACTIVE) {
                                Mono.empty()
                            } else {
                                sessions.consumeToken(hash)
                                    .then(Mono.defer { issue(current.sessionId, user.id, current.expiresAt) })
                            }
                        }
                    }
                }.singleOrEmpty()
            }
        }.switchIfEmpty(Mono.error(InvalidCredentialsException()))
            .onErrorMap(::databaseFailure) { AccountAuthenticationUnavailableException() }
    }

    fun logout(token: String?): Mono<Void> = Mono.defer {
        tokenHash(token).flatMap { hash ->
            sessions.findToken(hash).flatMap { initial ->
                transactions.execute {
                    users.findByIdForUpdate(initial.userId)
                        .flatMap { sessions.findToken(hash) }
                        .flatMap { sessions.revoke(it.sessionId) }
                }.then()
            }
        }.then().onErrorMap(::databaseFailure) { AccountAuthenticationUnavailableException() }
    }

    private fun issue(sessionId: UUID, userId: Long, expiresAt: Instant): Mono<SessionAuthentication> {
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        return sessions.insertToken(hash(raw), sessionId)
            .map { SessionAuthentication(accessToken(userId), raw, expiresAt) }
    }

    private fun accessToken(userId: Long): LoginResponse {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder().issuer(JwtConfiguration.ISSUER)
            .audience(listOf(JwtConfiguration.AUDIENCE)).subject(userId.toString())
            .issuedAt(now).expiresAt(now.plus(properties.accessTokenTtl)).build()
        val token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).build(), claims,
        )).tokenValue
        return LoginResponse(token, properties.accessTokenTtl.seconds)
    }

    private fun tokenHash(token: String?): Mono<String> =
        if (token != null && token.matches(Regex("[A-Za-z0-9_-]{43}"))) Mono.just(hash(token)) else Mono.empty()

    private fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)))

    private fun databaseFailure(error: Throwable): Boolean =
        error is DataAccessException || error is TransactionException

    fun currentAccount(userId: Long): Mono<CurrentAccountResponse> =
        users.findById(userId)
            .onErrorMap(DataAccessException::class.java) { AccountAuthenticationUnavailableException() }
            .switchIfEmpty(Mono.error(InvalidCredentialsException()))
            .filter { it.status == UserStatus.ACTIVE }
            .switchIfEmpty(Mono.error(AccessDeniedException()))
            .map { CurrentAccountResponse(it.id, it.login, it.role) }
}

internal class SessionAuthentication(
    val response: LoginResponse,
    val refreshToken: String,
    val expiresAt: Instant,
)
