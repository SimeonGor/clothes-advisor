package ru.itmo.clothesadvisor.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.service.user.ReactiveCurrentUserService

@Configuration
internal class SecurityConfiguration {
    @Bean
    fun wardrobeSecurity(
        http: ServerHttpSecurity,
        // Feign initializes WebFlux codecs while creating the account client.
        @Lazy users: ReactiveCurrentUserService,
    ): SecurityWebFilterChain {
        http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .csrf { it.disable() }.httpBasic { it.disable() }.formLogin { it.disable() }
            .logout { it.disable() }.requestCache { it.disable() }
            .authorizeExchange {
                it.pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                    "/actuator/health/**").permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/metrics/**").permitAll()
                    .pathMatchers("/api/**").authenticated()
                    .anyExchange().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { exchange, _ -> securityError(exchange, 401) }
                    .accessDeniedHandler { exchange, _ -> securityError(exchange, 403) }
            }
            .oauth2ResourceServer { resource ->
                resource.authenticationEntryPoint { exchange, error ->
                    val status = when ((error as? OAuth2AuthenticationException)?.error?.errorCode) {
                        "account_blocked" -> 403
                        "account_unavailable" -> 503
                        else -> 401
                    }
                    securityError(exchange, status)
                }.accessDeniedHandler { exchange, _ -> securityError(exchange, 403) }
                    .jwt { it.jwtAuthenticationConverter(CurrentUserJwtConverter(users)) }
            }
        return http.build()
    }
}

internal class CurrentUserJwtAuthentication(token: Jwt, val user: CurrentUser) :
    JwtAuthenticationToken(token, emptyList())

internal class CurrentUserJwtConverter(private val users: ReactiveCurrentUserService) :
    Converter<Jwt, Mono<AbstractAuthenticationToken>> {
    override fun convert(token: Jwt): Mono<AbstractAuthenticationToken> =
        Mono.defer { users.requireActive(token.subject!!.toLong()) }
            .map<AbstractAuthenticationToken> { CurrentUserJwtAuthentication(token, it) }
            .onErrorMap(EntityNotFoundException::class.java) { authenticationError("invalid_token") }
            .onErrorMap(AccessDeniedException::class.java) { authenticationError("account_blocked") }
            .onErrorMap(AccountUnavailableException::class.java) { authenticationError("account_unavailable") }

    private fun authenticationError(code: String) = OAuth2AuthenticationException(OAuth2Error(code))
}

internal fun securityError(exchange: ServerWebExchange, status: Int): Mono<Void> {
    val response = exchange.response
    response.statusCode = HttpStatus.valueOf(status)
    response.headers.contentType = MediaType.APPLICATION_JSON
    response.headers.cacheControl = "no-store"
    if (status == 401) response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
    val error = when (status) {
        401 -> "unauthorized"
        403 -> "forbidden"
        503 -> if (exchange.request.path.pathWithinApplication().value().startsWith("/api/v2/")) "ai_unavailable" else "service_unavailable"
        else -> "service_unavailable"
    }
    return response.writeWith(Mono.just(response.bufferFactory()
        .wrap("{\"error\":\"$error\"}".toByteArray(Charsets.UTF_8))))
}
