package ru.itmo.clothesadvisor.messaging

import io.micrometer.tracing.Span
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.observability.DurableTrace
import ru.itmo.clothesadvisor.service.ai.AiJobResults

@Component
@Profile("kafka")
internal class AiJobResultListener(
    private val results: AiJobResults, private val rejections: DeliveryRejections,
    private val trace: DurableTrace = DurableTrace.disabled,
) {
    @KafkaListener(topics = ["ai-selection.execution-events"])
    fun receive(record: ConsumerRecord<String, ByteArray>) {
        val event = try {
            AiExecutionEventDecoder.decode(record)
        } catch (invalid: InvalidExecutionEvent) {
            rejections.record(record, invalid.reason)
            return
        }
        val parent = DurableTrace.header(record.headers().lastHeader(DurableTrace.HEADER)?.value())
        trace.child(parent, "ai-selection.execution-events process", Span.Kind.CONSUMER) { traceparent ->
            results.apply(event, traceparent) { reason -> rejections.record(record, reason) }
        }
    }
}
