package ru.itmo.clothesadvisor.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cloud.netflix.eureka.TimeoutProperties
import org.springframework.core.io.FileSystemResource

class DiscoveryClientConfigurationTests {
    @Test
    fun `central configuration binds Eureka HTTP timeouts`() {

        // given
        val configuration = FileSystemResource("../config-repository/wardrobe-service/application.yml")
        val sources = YamlPropertySourceLoader().load("wardrobe-service", configuration)
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(TimeoutConfiguration::class.java)
                .withInitializer { context ->
                    sources.forEach { context.environment.propertySources.addLast(it) }
                }

        // when
        runner.run { context ->

            // then
            assertThat(context).hasNotFailed()
            val timeouts = context.getBean(TimeoutProperties::class.java)
            assertThat(timeouts.connectTimeout).isEqualTo(2000)
            assertThat(timeouts.connectRequestTimeout).isEqualTo(2000)
            assertThat(timeouts.socketTimeout).isEqualTo(5000)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TimeoutProperties::class)
    class TimeoutConfiguration
}
