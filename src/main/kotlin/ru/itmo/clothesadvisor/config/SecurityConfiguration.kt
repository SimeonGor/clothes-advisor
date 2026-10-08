package ru.itmo.clothesadvisor.config

import jakarta.servlet.DispatcherType
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.web.SecurityFilterChain
import ru.itmo.clothesadvisor.security.auth.ApiSecurityErrors
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService

@Configuration
@EnableMethodSecurity
internal class SecurityConfiguration {
    @Bean
    fun jwtKey(@Value("\${app.security.jwt.secret-base64:}") encoded: String): SecretKey {
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("JWT signing key must be valid Base64 containing at least 32 bytes")
        }
        require(bytes.size >= 32) { "JWT signing key must be valid Base64 containing at least 32 bytes" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(key: SecretKey): JwtEncoder =
        NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build()

    @Bean
    fun jwtDecoder(key: SecretKey, clock: Clock): JwtDecoder {
        val timestamps = JwtTimestampValidator(Duration.ZERO).apply {
            setClock(clock)
            setAllowEmptyExpiryClaim(false)
        }
        val claims = OAuth2TokenValidator<Jwt> { jwt ->
            if (jwt.audience == listOf(AUDIENCE) && jwt.expiresAt?.let { clock.instant().isBefore(it) } == true) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
            }
        }
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
            setJwtValidator(JwtValidators.createDefaultWithValidators(timestamps, JwtIssuerValidator(ISSUER), claims))
        }
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailsService(users: AppUserService): UserDetailsService = UserDetailsService { login ->
        val user = users.findByLogin(login) ?: throw UsernameNotFoundException("Invalid credentials")
        User.withUsername(requireNotNull(user.id).toString())
            .password(user.passwordHash)
            .roles(user.role.name)
            .disabled(user.status != UserStatus.ACTIVE)
            .build()
    }

    @Bean
    fun authenticationManager(userDetailsService: UserDetailsService, encoder: PasswordEncoder): AuthenticationManager =
        ProviderManager(DaoAuthenticationProvider(userDetailsService).apply { setPasswordEncoder(encoder) })

    @Bean
    fun securityFilterChain(http: HttpSecurity, users: AppUserService, environment: Environment): SecurityFilterChain {
        http.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                if (environment.acceptsProfiles(Profiles.of("local"))) {
                    it.requestMatchers(org.springframework.http.HttpMethod.GET,
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                    ).permitAll()
                }
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> ApiSecurityErrors.unauthorized(response) }
                    .accessDeniedHandler { _, response, _ -> ApiSecurityErrors.forbidden(response) }
            }
            .oauth2ResourceServer {
                it.authenticationEntryPoint { _, response, _ -> ApiSecurityErrors.unauthorized(response) }
                    .accessDeniedHandler { _, response, _ -> ApiSecurityErrors.forbidden(response) }
                    .jwt { jwt ->
                        jwt.jwtAuthenticationConverter { token ->
                            val id = token.subject?.toLongOrNull() ?: throw InvalidBearerTokenException("Invalid token")
                            val user = users.findById(id) ?: throw InvalidBearerTokenException("Invalid token")
                            if (user.status != UserStatus.ACTIVE) throw InvalidBearerTokenException("Invalid token")
                            UsernamePasswordAuthenticationToken.authenticated(
                                CurrentUser(id, user.login, user.role), null,
                                listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")),
                            )
                        }
                    }
            }
        return http.build()
    }

    companion object {
        const val ISSUER = "clothes-advisor"
        const val AUDIENCE = "clothes-advisor-api"
    }
}
