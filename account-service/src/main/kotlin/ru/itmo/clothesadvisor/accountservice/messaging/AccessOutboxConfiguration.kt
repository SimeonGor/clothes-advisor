package ru.itmo.clothesadvisor.accountservice.messaging

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("app.kafka.client-properties")
internal class AccessOutboxConfiguration {
    @Bean(destroyMethod = "close")
    fun accessProducer(@Value("\${app.kafka.client-properties}") clientFile: Path): Producer<String, String> {
        val properties = Properties()
        Files.newInputStream(clientFile).use(properties::load)
        return KafkaProducer(properties, StringSerializer(), StringSerializer())
    }

    @Bean
    fun accessOutboxRelay(database: DatabaseClient, producer: Producer<String, String>) =
        AccessOutboxRelay(database, producer)
}
