package ru.itmo.clothesadvisor.observability

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.otel.bridge.OtelSpan
import io.opentelemetry.api.trace.Span as OpenTelemetrySpan
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter

class DurableTrace(private val tracer: Tracer) {
    fun <T> child(parent: String?, name: String, kind: Span.Kind, action: (String?) -> T): T {
        if (tracer === Tracer.NOOP || parent == null || parent.length != 55) return action(null)
        val context = propagation.extract(Context.root(), parent, getter)
        val remote = OpenTelemetrySpan.fromContext(context)
        if (!remote.spanContext.isValid) return action(null)

        val span = tracer.spanBuilder().setParent(OtelSpan.fromOtel(remote, context).context())
            .name(name).kind(kind).start()
        try {
            tracer.withSpan(span).use { return action(capture()) }
        } catch (failure: Throwable) {
            // Exceptions from dependencies can contain URLs, credentials or payloads.
            OtelSpan.toOtel(span).setStatus(io.opentelemetry.api.trace.StatusCode.ERROR)
            throw failure
        } finally {
            span.end()
        }
    }

    companion object {
        const val HEADER = "traceparent"
        val disabled = DurableTrace(Tracer.NOOP)
        private val propagation = W3CTraceContextPropagator.getInstance()
        private val getter = object : TextMapGetter<String> {
            override fun keys(carrier: String) = listOf(HEADER)
            override fun get(carrier: String?, key: String) = carrier.takeIf { key == HEADER }
        }

        fun capture(): String? {
            var parent: String? = null
            propagation.inject(Context.current(), Unit) { _, key, value -> if (key == HEADER) parent = value }
            return parent
        }

        fun header(bytes: ByteArray?): String? =
            bytes?.takeIf { it.size == 55 }?.toString(Charsets.US_ASCII)
    }
}
