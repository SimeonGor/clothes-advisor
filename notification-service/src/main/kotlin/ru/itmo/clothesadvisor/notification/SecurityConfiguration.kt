package ru.itmo.clothesadvisor.notification

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository

@Configuration
internal class SecurityConfiguration {
    @Bean
    fun security(http: ServerHttpSecurity): SecurityWebFilterChain = http
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .csrf { it.disable() }.httpBasic { it.disable() }.formLogin { it.disable() }
        .logout { it.disable() }.requestCache { it.disable() }
        .authorizeExchange {
            it.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/actuator/metrics/notification.duplicates").permitAll()
                .pathMatchers("/api/me/notifications", "/api/me/notifications/**").authenticated()
                .anyExchange().denyAll()
        }
        .oauth2ResourceServer { it.jwt {} }.build()
}
