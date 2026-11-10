package ru.itmo.clothesadvisor.accountservice.messaging

import org.apache.kafka.clients.producer.Producer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.ProducerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.EnableScheduling
import ru.itmo.clothesadvisor.observability.DurableTrace

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("kafka")
internal class AccessOutboxConfiguration {
    @Bean(destroyMethod = "close")
    fun accessProducer(factory: ProducerFactory<String, String>): Producer<String, String> =
        factory.createProducer()

    @Bean
    fun accessOutboxRelay(database: DatabaseClient, producer: Producer<String, String>, trace: DurableTrace) =
        AccessOutboxRelay(database, producer, trace)
}
