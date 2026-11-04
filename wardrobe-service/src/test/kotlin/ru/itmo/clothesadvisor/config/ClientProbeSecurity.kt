package ru.itmo.clothesadvisor.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.security.config.web.server.ServerHttpSecurity

// Only imported by isolated transport probes; application HTTP tests use production security.
@TestConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ClientProbeSecurity {
    @Bean
    fun probeSecurity(http: ServerHttpSecurity) = http.csrf { it.disable() }
        .authorizeExchange { it.anyExchange().permitAll() }.build()
}
