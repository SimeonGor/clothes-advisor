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
internal class SelectionPromptPollingConfiguration {
    @Bean
    fun selectionPromptPoller(
        @Qualifier("configDataConfigClientProperties") properties: ConfigClientProperties,
        environment: Environment,
        @Qualifier("outfitPromptSettings") outfitSettings: SelectionPromptSettings,
        @Qualifier("capsulePromptSettings") capsuleSettings: SelectionPromptSettings,
    ) = SelectionPromptPoller(properties, environment, outfitSettings, capsuleSettings)
}

internal class SelectionPromptPoller(
    properties: ConfigClientProperties,
    private val environment: Environment,
    private val outfitSettings: SelectionPromptSettings,
    private val capsuleSettings: SelectionPromptSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val locator = ConfigServicePropertySourceLocator(ConfigClientProperties(environment).also {
        BeanUtils.copyProperties(properties, it)
        it.profile = environment.getProperty("spring.cloud.config.profile")
            ?: (environment.activeProfiles.ifEmpty { environment.defaultProfiles }).joinToString(",")
        // Throw instead of logging the locator's warning with a remote exception/body.
        it.isFailFast = true
    })

    @Scheduled(
        fixedDelayString = "\${app.openai.prompt.poll-interval:30s}",
        initialDelayString = "\${app.openai.prompt.poll-interval:30s}",
    )
    fun poll() {
        val source = try {
            requireNotNull(locator.locate(environment))
        } catch (_: Exception) {
            logger.warn("Selection prompts fetch failed; keeping last working prompts")
            return
        }
        for ((name, settings) in listOf("Outfit" to outfitSettings, "Capsule" to capsuleSettings)) {
            try {
                if (settings.apply(source)) {
                    logger.info("{} prompt updated to version {}", name, settings.snapshot()?.version)
                }
            } catch (_: Exception) {
                logger.warn("{} prompt update rejected; keeping last working prompt", name)
            }
        }
    }
}
