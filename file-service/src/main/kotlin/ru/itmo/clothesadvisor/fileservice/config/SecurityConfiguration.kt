package ru.itmo.clothesadvisor.fileservice.config

import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository

@Configuration(proxyBeanMethods = false)
internal class SecurityConfiguration {
    @Bean
    fun passwordEncoder(): PasswordEncoder = Pbkdf2PasswordEncoder(
        "", 16, 600_000, Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256,
    )

    @Bean
    fun serviceAccounts(
        @Value("\${app.auth.wardrobe-password-file}") wardrobeFile: String,
        @Value("\${app.auth.recommendation-password-file}") recommendationFile: String,
        encoder: PasswordEncoder,
    ): MapReactiveUserDetailsService {
        val wardrobe = readPassword(wardrobeFile)
        val recommendation = readPassword(recommendationFile)
        require(wardrobe != recommendation) { "File service account passwords must be distinct" }
        return MapReactiveUserDetailsService(
            User.withUsername("wardrobe-service").password(encoder.encode(wardrobe))
                .authorities("file:read", "file:write").build(),
            User.withUsername("recommendation-service").password(encoder.encode(recommendation))
                .authorities("file:read").build(),
        )
    }

    private fun readPassword(location: String): String {
        val password = try {
            Files.readString(Path.of(location)).removeSuffix("\n").removeSuffix("\r")
        } catch (_: Exception) {
            throw IllegalStateException("File service account password is unreadable")
        }
        require(password.isNotBlank()) { "File service account password is blank" }
        return password
    }

    @Bean
    fun fileSecurity(http: ServerHttpSecurity): SecurityWebFilterChain =
        http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .csrf { it.disable() }.formLogin { it.disable() }.logout { it.disable() }
            .requestCache { it.disable() }.httpBasic(Customizer.withDefaults())
            .authorizeExchange {
                it.pathMatchers(
                    HttpMethod.GET,
                    "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                ).permitAll()
                    .pathMatchers(HttpMethod.PUT, "/api/files/*").hasAuthority("file:write")
                    .pathMatchers(HttpMethod.GET, "/api/files/*").hasAuthority("file:read")
                    .anyExchange().denyAll()
            }.build()
}
