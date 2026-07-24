package com.contractguard.adapter.observability;

import com.contractguard.application.port.ObservabilityPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Map;

/**
 * Backs {@link ObservabilityPort} with Micrometer's {@link Observation} API, which produces a
 * Prometheus timer metric and an OpenTelemetry trace span from the same instrumentation call
 * (FR-029). Tags are recorded as high-cardinality (trace-only): a run ID is unbounded, so it must
 * never become a Prometheus label — the per-step {@code Timer} metric stays name-keyed instead.
 */
public class MicrometerObservability implements ObservabilityPort {

    private static final String TAG_PROMPT = "prompt";

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public MicrometerObservability(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    @Override
    public SpanHandle startSpan(String name, Map<String, String> tags) {
        Observation observation = Observation.createNotStarted(name, observations);
        tags.forEach(observation::highCardinalityKeyValue);
        observation.start();
        // Opening the scope is what makes this the "current" observation, which is how a nested
        // startSpan() call picks it up as a parent -- without it every span is its own root trace.
        Observation.Scope scope = observation.openScope();
        return new MicrometerSpanHandle(observation, scope);
    }

    @Override
    public void recordLlmUsage(String promptName, int promptTokens, int completionTokens) {
        if (promptTokens >= 0) {
            meters.summary("contractguard.llm.tokens", TAG_PROMPT, promptName, "type", TAG_PROMPT)
                    .record(promptTokens);
        }
        if (completionTokens >= 0) {
            meters.summary("contractguard.llm.tokens", TAG_PROMPT, promptName, "type", "completion")
                    .record(completionTokens);
        }
    }

    private record MicrometerSpanHandle(Observation observation, Observation.Scope scope) implements SpanHandle {

        @Override
        public void recordError(String message) {
            observation.error(new IllegalStateException(message));
        }

        @Override
        public void close() {
            scope.close();
            observation.stop();
        }
    }
}
