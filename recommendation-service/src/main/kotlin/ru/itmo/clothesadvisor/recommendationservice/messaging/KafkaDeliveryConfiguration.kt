package ru.itmo.clothesadvisor.recommendationservice.messaging

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.LoggingProducerListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.util.backoff.FixedBackOff

@Configuration(proxyBeanMethods = false)
@Profile("kafka")
@EnableScheduling
class KafkaDeliveryConfiguration {
    @Bean
    fun producerListener() = LoggingProducerListener<Any, Any>().apply { setIncludeContents(false) }

    // A listener returns normally only after its effect or rejection is durable.
    @Bean
    fun deliveryErrorHandler() = DefaultErrorHandler(FixedBackOff(3000, FixedBackOff.UNLIMITED_ATTEMPTS)).apply {
        setClassifications(emptyMap(), true)
    }
}
