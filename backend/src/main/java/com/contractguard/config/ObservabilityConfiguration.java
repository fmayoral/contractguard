package com.contractguard.config;

import com.contractguard.adapter.observability.MicrometerObservability;
import com.contractguard.application.port.ObservabilityPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires tracing/metrics (FR-029, ADR-0011): one {@link ObservabilityPort} backed by Micrometer. */
@Configuration
public class ObservabilityConfiguration {

    /** Spans are exported to the log by default (ADR-0011); set an OTLP endpoint for real tracing infra. */
    @Bean
    public SpanExporter otelSpanExporter() {
        return LoggingSpanExporter.create();
    }

    @Bean
    public ObservabilityPort observabilityPort(ObservationRegistry observations, MeterRegistry meters) {
        return new MicrometerObservability(observations, meters);
    }
}
