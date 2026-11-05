package ru.itmo.clothesadvisor.accountservice.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.auth")
internal data class AuthenticationProperties(
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
) {
    init {
        require(accessTokenTtl.seconds > 0 && accessTokenTtl.nano == 0) {
            "app.auth.access-token-ttl must be positive and use whole seconds"
        }
        require(refreshTokenTtl.seconds > 0 && refreshTokenTtl.nano == 0) {
            "app.auth.refresh-token-ttl must be positive and use whole seconds"
        }
    }
}
