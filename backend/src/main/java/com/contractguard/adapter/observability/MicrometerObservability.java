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
        return new MicrometerSpanHandle(observation);
    }

    @Override
    public void recordLlmUsage(String promptName, int promptTokens, int completionTokens) {
        if (promptTokens >= 0) {
            meters.summary("contractguard.llm.tokens", "prompt", promptName, "type", "prompt")
                    .record(promptTokens);
        }
        if (completionTokens >= 0) {
            meters.summary("contractguard.llm.tokens", "prompt", promptName, "type", "completion")
                    .record(completionTokens);
        }
    }

    private record MicrometerSpanHandle(Observation observation) implements SpanHandle {

        @Override
        public void recordError(String message) {
            observation.error(new IllegalStateException(message));
        }

        @Override
        public void close() {
            observation.stop();
        }
    }
}
