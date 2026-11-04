package ru.itmo.clothesadvisor.apigateway

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Configuration
internal class SecurityConfiguration {
    @Bean
    fun gatewaySecurity(http: ServerHttpSecurity): SecurityWebFilterChain {
        http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .csrf { it.disable() }.httpBasic { it.disable() }.formLogin { it.disable() }
            .logout { it.disable() }.requestCache { it.disable() }
            .authorizeExchange {
                it.pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                    .pathMatchers("/", "/index.html", "/app", "/app/**", "/v3/api-docs/**",
                        "/swagger-ui/**", "/swagger-ui.html", "/actuator/health/**").permitAll()
                    .pathMatchers("/api/**").authenticated()
                    .anyExchange().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { exchange, _ -> securityError(exchange, 401) }
                    .accessDeniedHandler { exchange, _ -> securityError(exchange, 403) }
            }
            .oauth2ResourceServer {
                it.authenticationEntryPoint { exchange, _ -> securityError(exchange, 401) }
                    .accessDeniedHandler { exchange, _ -> securityError(exchange, 403) }
                    .jwt { }
            }
        return http.build()
    }
}

internal fun securityError(exchange: ServerWebExchange, status: Int): Mono<Void> {
    val response = exchange.response
    response.statusCode = HttpStatus.valueOf(status)
    response.headers.contentType = MediaType.APPLICATION_JSON
    response.headers.cacheControl = "no-store"
    if (status == 401) response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
    val error = if (status == 401) "unauthorized" else "forbidden"
    return response.writeWith(Mono.just(response.bufferFactory()
        .wrap("{\"error\":\"$error\"}".toByteArray(Charsets.UTF_8))))
}
