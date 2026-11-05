package ru.itmo.clothesadvisor.accountservice.config

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class AuthenticationPropertiesTests {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `binds custom access and refresh durations`() {
        // given
        val configured = runner.withPropertyValues(
            "app.auth.access-token-ttl=20m",
            "app.auth.refresh-token-ttl=3d",
        )

        // when
        configured.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val properties = context.getBean(AuthenticationProperties::class.java)
            assertThat(properties.accessTokenTtl).isEqualTo(Duration.ofMinutes(20))
            assertThat(properties.refreshTokenTtl).isEqualTo(Duration.ofDays(3))
        }
    }

    @ParameterizedTest
    @CsvSource(
        "access-token-ttl, 0s", "refresh-token-ttl, 0s",
        "access-token-ttl, -1s", "refresh-token-ttl, -1s",
        "access-token-ttl, 1500ms", "refresh-token-ttl, 1500ms",
        "access-token-ttl, invalid", "refresh-token-ttl, invalid",
    )
    fun `invalid duration fails startup`(property: String, value: String) {
        // given
        val configured = runner.withPropertyValues(
            "app.auth.access-token-ttl=20m",
            "app.auth.refresh-token-ttl=3d",
            "app.auth.$property=$value",
        )

        // when
        configured.run { context ->
            // then
            assertThat(context).hasFailed()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["access-token-ttl", "refresh-token-ttl"])
    fun `missing duration fails startup`(configuredProperty: String) {
        // given
        val configured = runner.withPropertyValues("app.auth.$configuredProperty=1h")

        // when
        configured.run { context ->
            // then
            assertThat(context).hasFailed()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthenticationProperties::class)
    internal class PropertiesConfiguration
}
