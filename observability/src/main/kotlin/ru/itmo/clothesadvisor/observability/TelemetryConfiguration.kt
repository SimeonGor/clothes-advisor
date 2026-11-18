package ru.itmo.clothesadvisor.observability

import io.micrometer.tracing.Tracer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration
class TelemetryConfiguration {
    @Bean
    fun durableTrace(tracer: Tracer): DurableTrace = DurableTrace(tracer)

    @Bean
    @ConditionalOnProperty("management.opentelemetry.logging.export.otlp.endpoint")
    fun connectLogAppender(openTelemetry: OpenTelemetry) = InitializingBean {
        OpenTelemetryAppender.install(openTelemetry)
    }
}
