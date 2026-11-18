package ru.itmo.clothesadvisor.recommendationservice.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono

@Repository
@ConditionalOnProperty("spring.r2dbc.url")
internal class DeliveryRejections(private val database: DatabaseClient) {
    fun record(record: ConsumerRecord<*, *>, reason: String): Mono<Void> =
        database.sql(
            """
            INSERT INTO kafka_delivery_rejection (topic, partition_id, record_offset, reason)
            VALUES (:topic, :partition, :offset, :reason) ON CONFLICT DO NOTHING
            """,
        ).bind("topic", record.topic()).bind("partition", record.partition()).bind("offset", record.offset())
            .bind("reason", reason).fetch().rowsUpdated().then()
}
