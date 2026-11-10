package ru.itmo.clothesadvisor.notification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.stereotype.Component
import org.springframework.util.backoff.FixedBackOff

@Configuration
@Profile("!test")
@EnableKafka
internal class KafkaConfiguration {
    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<ByteArray, ByteArray> {
        val credentials = Properties()
        Files.newInputStream(Path.of("/run/kafka-client/client.properties")).use(credentials::load)
        val config = credentials.stringPropertyNames().associateWithTo(mutableMapOf<String, Any>()) {
            credentials.getProperty(it)
        }
        config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = "false"
        config[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        config[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG] = "false"
        // Keep the maximum blocking database time for a poll below max.poll.interval.ms.
        config[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = "5"
        return ConcurrentKafkaListenerContainerFactory<ByteArray, ByteArray>().apply {
            setConsumerFactory(DefaultKafkaConsumerFactory(config, ByteArrayDeserializer(), ByteArrayDeserializer()))
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            setCommonErrorHandler(DefaultErrorHandler(FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS)))
        }
    }
}

@Component
internal class KafkaEvents(private val events: DomainEvents, private val notifications: Notifications) {
    @KafkaListener(topics = [ACCESS, REQUESTS, OUTFITS, RATINGS, JOBS], groupId = "notification-service.events")
    fun consume(record: ConsumerRecord<ByteArray, ByteArray>) {
        val notification = try {
            events.notification(record.topic(), record.value())
        } catch (error: InvalidDomainEvent) {
            notifications.reject(record.topic(), record.partition(), record.offset(), error.reason)
                .block(Duration.ofSeconds(30))
            return
        }
        // This is a dedicated Kafka listener thread. Returning acknowledges the record.
        notifications.save(notification).block(Duration.ofSeconds(30))
    }
}
