package ru.itmo.clothesadvisor.config

import java.util.concurrent.ScheduledFuture
import org.slf4j.LoggerFactory
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.bus.event.RefreshRemoteApplicationEvent
import org.springframework.cloud.config.client.ConfigClientProperties
import org.springframework.cloud.config.client.ConfigServicePropertySourceLocator
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["spring.cloud.bus.enabled"], havingValue = "true")
@ConditionalOnProperty(name = ["spring.cloud.config.enabled"], matchIfMissing = true)
@EnableScheduling
@Import(SelectionPromptRefresh::class)
internal class SelectionPromptRefreshConfiguration

internal class SelectionPromptRefresh(
    @Qualifier("configDataConfigClientProperties") properties: ConfigClientProperties,
    private val environment: Environment,
    private val scheduler: TaskScheduler,
    @Qualifier("outfitPromptSettings") private val outfitSettings: SelectionPromptSettings,
    @Qualifier("capsulePromptSettings") private val capsuleSettings: SelectionPromptSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val locator = ConfigServicePropertySourceLocator(ConfigClientProperties(environment).also {
        BeanUtils.copyProperties(properties, it)
        it.profile = environment.getProperty("spring.cloud.config.profile")
            ?: (environment.activeProfiles.ifEmpty { environment.defaultProfiles }).joinToString(",")
        it.isFailFast = true
    })
    private var retry: ScheduledFuture<*>? = null

    @EventListener(RefreshRemoteApplicationEvent::class)
    @Synchronized
    fun refresh() {
        retry?.cancel(false)
        retry = null
        val source = try {
            requireNotNull(locator.locate(environment))
        } catch (_: Exception) {
            logger.warn("Configuration fetch failed; retaining current settings and retrying")
            retry = scheduler.schedule(::refresh, scheduler.clock.instant().plusSeconds(5))
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
