package ru.itmo.clothesadvisor.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
internal class DeliveryRejections(private val jdbc: JdbcTemplate) {
    fun record(record: ConsumerRecord<*, *>, reason: String) {
        jdbc.update("""INSERT INTO kafka_delivery_rejection (topic,partition_id,record_offset,reason)
            VALUES (?,?,?,?) ON CONFLICT DO NOTHING""", record.topic(), record.partition(), record.offset(), reason)
    }
}
