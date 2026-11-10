package ru.itmo.clothesadvisor.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.service.ai.AiJobResults

@Component
@Profile("kafka")
internal class AiJobResultListener(private val results: AiJobResults, private val rejections: DeliveryRejections) {
    @KafkaListener(topics = ["ai-selection.execution-events"], groupId = "wardrobe-service.ai-selection")
    fun receive(record: ConsumerRecord<String, ByteArray>) {
        val event = try {
            AiExecutionEventDecoder.decode(record)
        } catch (invalid: InvalidExecutionEvent) {
            rejections.record(record, invalid.reason)
            return
        }
        results.apply(event) { reason -> rejections.record(record, reason) }
    }
}
