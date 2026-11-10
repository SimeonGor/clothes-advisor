package ru.itmo.clothesadvisor.notification

import io.micrometer.tracing.Span
import java.time.Duration
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.stereotype.Component
import org.springframework.util.backoff.FixedBackOff
import ru.itmo.clothesadvisor.observability.DurableTrace

@Configuration(proxyBeanMethods = false)
@Profile("!test")
internal class KafkaConfiguration {
    @Bean
    fun deliveryErrorHandler() = DefaultErrorHandler(FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS))
}

@Component
internal class KafkaEvents(
    private val events: DomainEvents, private val notifications: Notifications,
    private val trace: DurableTrace = DurableTrace.disabled,
) {
    @KafkaListener(topics = [ACCESS, REQUESTS, OUTFITS, RATINGS, JOBS])
    fun consume(record: ConsumerRecord<ByteArray, ByteArray>) {
        val notification = try {
            events.notification(record.topic(), record.value())
        } catch (error: InvalidDomainEvent) {
            notifications.reject(record.topic(), record.partition(), record.offset(), error.reason)
                .block(Duration.ofSeconds(30))
            return
        }
        // This is a dedicated Kafka listener thread. Returning acknowledges the record.
        val parent = DurableTrace.header(record.headers().lastHeader(DurableTrace.HEADER)?.value())
        trace.child(parent, "${record.topic()} notify", Span.Kind.CONSUMER) {
            notifications.save(notification).block(Duration.ofSeconds(30))
        }
    }
}
