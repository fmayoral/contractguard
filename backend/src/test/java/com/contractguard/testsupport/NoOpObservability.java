package com.contractguard.testsupport;

import com.contractguard.application.port.ObservabilityPort;

import java.util.Map;

/** Discards everything; application-service tests assert on domain/event-log state, not spans. */
public class NoOpObservability implements ObservabilityPort {

    private static final SpanHandle HANDLE = new SpanHandle() {
        @Override
        public void recordError(String message) {
        }

        @Override
        public void close() {
        }
    };

    @Override
    public SpanHandle startSpan(String name, Map<String, String> tags) {
        return HANDLE;
    }

    @Override
    public void recordLlmUsage(String promptName, int promptTokens, int completionTokens) {
    }
}
