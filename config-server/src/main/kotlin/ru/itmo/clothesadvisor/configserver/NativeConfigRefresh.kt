package ru.itmo.clothesadvisor.configserver

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.bus.BusConstants
import org.springframework.cloud.bus.ServiceMatcher
import org.springframework.cloud.bus.event.Destination
import org.springframework.cloud.bus.event.RefreshRemoteApplicationEvent
import org.springframework.cloud.stream.function.StreamBridge
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@Profile("native")
@ConditionalOnProperty(name = ["spring.cloud.bus.enabled"], havingValue = "true")
@EnableScheduling
internal class NativeConfigRefreshConfiguration {
    @Bean
    fun nativeConfigRefresh(
        @Value("\${app.config-repository:../config-repository}") repository: Path,
        matcher: ServiceMatcher,
        destinations: Destination.Factory,
        bridge: StreamBridge,
    ) = NativeConfigRefresh(repository) { service ->
        val event = RefreshRemoteApplicationEvent(this, matcher.busId, destinations.getDestination(service))
        check(bridge.send(BusConstants.OUTPUT, event))
    }
}

internal class NativeConfigRefresh(private val repository: Path, private val publish: (String) -> Unit) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val published = mutableMapOf<String, Map<String, String>>()

    @Scheduled(fixedDelay = 2000)
    fun scan() {
        for (service in listOf("recommendation-service", "wardrobe-service")) {
            try {
                val files = Files.list(repository.resolve(service)).use { paths ->
                    paths.filter { it.fileName.toString().matches(configFile) }.toList()
                }
                val fingerprint = files.associate { file ->
                    file.fileName.toString() to java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)),
                    )
                }
                if (fingerprint != published[service]) {
                    publish(service)
                    // Record only after Kafka acknowledges. A restart reconciles each service once.
                    published[service] = fingerprint
                    logger.info("Published configuration refresh for {}", service)
                }
            } catch (_: Exception) {
                logger.warn("Could not reconcile configuration for {}; will retry", service)
            }
        }
    }

    private val configFile = Regex("""application(?:-[\w.-]+)?\.(?:ya?ml|properties)""")
}
