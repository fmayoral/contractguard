package com.contractguard.application.port;

import java.util.Map;

/**
 * Distributed tracing and LLM usage recording (FR-029). Kept as a port —
 * rather than calling a metrics/tracing library directly — so the
 * application layer stays framework-free; the adapter decides whether spans
 * become OpenTelemetry traces, Prometheus metrics, both or neither.
 */
public interface ObservabilityPort {

    /** Starts a span for one pipeline node; callers close it (try-with-resources) when the work ends. */
    SpanHandle startSpan(String name, Map<String, String> tags);

    /** @param promptTokens / completionTokens negative when the provider reported no usage */
    void recordLlmUsage(String promptName, int promptTokens, int completionTokens);

    interface SpanHandle extends AutoCloseable {

        /** Marks the span as failed; does not throw or stop the span, just annotates it. */
        void recordError(String message);

        @Override
        void close();
    }
}
