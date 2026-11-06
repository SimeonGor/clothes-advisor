package ru.itmo.clothesadvisor.accountservice.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder

@Configuration(proxyBeanMethods = false)
internal class PasswordConfiguration {
    @Bean
    fun passwordEncoder(): PasswordEncoder = DelegatingPasswordEncoder(
        FORMAT_ID,
        mapOf(
            FORMAT_ID to Pbkdf2PasswordEncoder(
                "", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256,
            ),
        ),
    )

    companion object {
        const val FORMAT_ID = "pbkdf2-sha256-600000"
    }
}
