package ru.itmo.clothesadvisor.config

import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers

@Configuration(proxyBeanMethods = false)
internal class AiJobValidationSecurity {
    @Bean
    @Order(0)
    fun validationSecurity(
        http: ServerHttpSecurity,
        @Value("\${app.ai.validation-password-file}") passwordFile: String,
    ): SecurityWebFilterChain {
        val password = Files.readString(Path.of(passwordFile)).removeSuffix("\n").removeSuffix("\r")
        require(password.isNotBlank()) { "AI validation password is blank" }
        val encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
        val account = User.withUsername("recommendation-service").password(encoder.encode(password))
            .authorities("ai:validate").build()
        val manager = UserDetailsRepositoryReactiveAuthenticationManager(MapReactiveUserDetailsService(account))
        manager.setPasswordEncoder(encoder)
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(
            HttpMethod.POST, "/internal/ai-jobs/*/validate-input",
        )).securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .csrf { it.disable() }.logout { it.disable() }.requestCache { it.disable() }.formLogin { it.disable() }
            .httpBasic { it.authenticationManager(manager) }
            .authorizeExchange { it.anyExchange().hasAuthority("ai:validate") }.build()
    }
}
