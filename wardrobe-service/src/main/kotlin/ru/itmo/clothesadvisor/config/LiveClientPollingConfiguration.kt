package ru.itmo.clothesadvisor.config

import org.slf4j.LoggerFactory
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.config.client.ConfigClientProperties
import org.springframework.cloud.config.client.ConfigServicePropertySourceLocator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.cloud.config", name = ["enabled"], matchIfMissing = true)
@EnableScheduling
internal class LiveClientPollingConfiguration {
    @Bean
    fun clientSettingsPoller(
        @Qualifier("configDataConfigClientProperties") properties: ConfigClientProperties,
        environment: Environment,
        settings: LiveClientSettings,
    ) = ClientSettingsPoller(properties, environment, settings)
}

internal class ClientSettingsPoller(
    properties: ConfigClientProperties,
    private val environment: Environment,
    private val settings: LiveClientSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val locator = ConfigServicePropertySourceLocator(ConfigClientProperties(environment).also {
        BeanUtils.copyProperties(properties, it)
        it.profile = environment.getProperty("spring.cloud.config.profile")
            ?: (environment.activeProfiles.ifEmpty { environment.defaultProfiles }).joinToString(",")
        // Throw instead of the locator's warning containing a remote exception/body.
        it.isFailFast = true
    })

    @Scheduled(
        fixedDelayString = "\${app.client-config.poll-interval:30s}",
        initialDelayString = "\${app.client-config.poll-interval:30s}",
    )
    fun poll() {
        try {
            val source = requireNotNull(locator.locate(environment))
            if (settings.apply(source)) logger.info("External client settings updated")
        } catch (_: Exception) {
            logger.warn("External client settings update rejected; keeping last working settings")
        }
    }
}
