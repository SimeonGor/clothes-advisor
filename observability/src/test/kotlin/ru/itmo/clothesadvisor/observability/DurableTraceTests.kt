package ru.itmo.clothesadvisor.observability

import io.micrometer.tracing.Span
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DurableTraceTests {
    private val traceId = "0123456789abcdef0123456789abcdef"
    private val parentId = "1020304050607080"

    @Test
    fun `child preserves remote lineage and restores scope even on failure`() {
        val ended = mutableListOf<SpanData>()
        val exporter = object : SpanExporter {
            override fun export(spans: Collection<SpanData>): CompletableResultCode {
                ended.addAll(spans)
                return CompletableResultCode.ofSuccess()
            }
            override fun flush() = CompletableResultCode.ofSuccess()
            override fun shutdown() = CompletableResultCode.ofSuccess()
        }
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().use { sdk ->
            val tracer = OtelTracer(sdk.get("test"), OtelCurrentTraceContext()) {}
            val trace = DurableTrace(tracer)
            val parent = "00-$traceId-$parentId-01"
            assertThatThrownBy {
                trace.child(parent, "publish", Span.Kind.PRODUCER) { child ->
                    assertThat(child).isEqualTo("00-$traceId-${tracer.currentSpan()!!.context().spanId()}-01").isNotEqualTo(parent)
                    throw IllegalStateException("test")
                }
            }.isInstanceOf(IllegalStateException::class.java)
            val span = ended.single()
            assertThat(span.parentSpanId).isEqualTo(parentId)
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.events).isEmpty()
            assertThat(DurableTrace.capture()).isNull()
        }
    }

    @Test
    fun `old and malformed transport metadata still delivers without a child`() {
        SdkTracerProvider.builder().build().use { sdk ->
            val trace = DurableTrace(OtelTracer(sdk.get("test"), OtelCurrentTraceContext()) {})
            for (header in listOf(null, ByteArray(4096), "00-${"0".repeat(32)}-$parentId-01".toByteArray())) {
                val result = trace.child(DurableTrace.header(header), "process", Span.Kind.CONSUMER) { child ->
                    assertThat(child).isNull()
                    assertThat(DurableTrace.capture()).isNull()
                    "delivered"
                }
                assertThat(result).isEqualTo("delivered")
            }
        }
    }
}
