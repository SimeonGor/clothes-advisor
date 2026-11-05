package ru.itmo.clothesadvisor.accountservice.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@Configuration
internal class SecurityConfiguration {
    @Bean
    fun accountSecurity(http: ServerHttpSecurity, users: AppUserRepository, cookies: RefreshCookie): SecurityWebFilterChain {
        val authPaths = arrayOf("/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
        val authPostMatcher = pathMatchers(HttpMethod.POST, *authPaths)
        val refreshMatcher = pathMatchers(HttpMethod.POST, "/api/auth/refresh")

        http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .csrf { it.disable() }.httpBasic { it.disable() }.formLogin { it.disable() }
            .logout { it.disable() }.requestCache { it.disable() }
            .addFilterBefore(WebFilter { exchange, chain ->
                authPostMatcher.matches(exchange).flatMap { match ->
                    if (!match.isMatch) return@flatMap chain.filter(exchange)

                    exchange.response.headers.cacheControl = "no-store"
                    exchange.response.beforeCommit {
                        if (exchange.response.statusCode != HttpStatus.UNAUTHORIZED) {
                            Mono.empty()
                        } else refreshMatcher.matches(exchange).doOnNext {
                            if (it.isMatch) exchange.response.addCookie(cookies.clear())
                        }.then()
                    }

                    if (exchange.request.headers["X-Auth-Request"] != listOf("1")) {
                        securityError(exchange, 403)
                    } else {
                        chain.filter(exchange)
                    }
                }
            }, SecurityWebFiltersOrder.AUTHENTICATION)
            .authorizeExchange {
                it.pathMatchers(HttpMethod.POST, *authPaths).permitAll()
                    .pathMatchers("/internal/**", "/v3/api-docs/**", "/swagger-ui/**",
                        "/actuator/health/**").permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/metrics/**").permitAll()
                    .pathMatchers("/api/admin/**").hasRole("ADMIN")
                    .pathMatchers("/api/me/stylist-access/**", "/api/stylists").hasRole("USER")
                    .pathMatchers("/api/stylist/clients").hasRole("STYLIST")
                    .pathMatchers("/api/auth/me").hasAnyRole("USER", "STYLIST", "ADMIN")
                    .anyExchange().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { exchange, _ -> securityError(exchange, 401) }
                    .accessDeniedHandler { exchange, _ -> securityError(exchange, 403) }
            }
            .oauth2ResourceServer { resource ->
                resource.authenticationEntryPoint { exchange, _ -> securityError(exchange, 401) }
                    .accessDeniedHandler { exchange, _ -> securityError(exchange, 403) }
                    .jwt { jwt ->
                        jwt.jwtAuthenticationConverter { token ->
                            users.findById(token.subject!!.toLong())
                                .onErrorMap(DataAccessException::class.java) {
                                    AccountAuthenticationUnavailableException()
                                }
                                .switchIfEmpty(Mono.error(OAuth2AuthenticationException("invalid_token")))
                                .map<AbstractAuthenticationToken> { user ->
                                    val authorities = if (user.status == UserStatus.ACTIVE) {
                                        listOf(SimpleGrantedAuthority("ROLE_${user.role}"))
                                    } else emptyList()
                                    JwtAuthenticationToken(token, authorities)
                                }
                        }
                    }
            }
        return http.build()
    }
}

internal class AccountAuthenticationUnavailableException : RuntimeException()

internal fun securityError(exchange: ServerWebExchange, status: Int): Mono<Void> {
    val response = exchange.response
    response.statusCode = HttpStatus.valueOf(status)
    response.headers.contentType = MediaType.APPLICATION_JSON
    response.headers.cacheControl = "no-store"
    if (status == 401) response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
    val error = when (status) {
        401 -> "unauthorized"
        403 -> "forbidden"
        else -> "service_unavailable"
    }
    return response.writeWith(Mono.just(response.bufferFactory()
        .wrap("{\"error\":\"$error\"}".toByteArray(Charsets.UTF_8))))
}
