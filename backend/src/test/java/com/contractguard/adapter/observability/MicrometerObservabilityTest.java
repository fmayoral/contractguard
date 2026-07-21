package com.contractguard.adapter.observability;

import com.contractguard.application.port.ObservabilityPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerObservabilityTest {

    private final TestObservationRegistry observations = TestObservationRegistry.create();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final MicrometerObservability observability = new MicrometerObservability(observations, meters);

    @Test
    void spanIsStartedTaggedAndStoppedOnClose() {
        try (ObservabilityPort.SpanHandle span = observability.startSpan("diff",
                Map.of("runId", "run-1", "repositoryId", "customer-consumer"))) {
            assertThat(span).isNotNull();
        }

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("diff")
                .that()
                .hasHighCardinalityKeyValue("runId", "run-1")
                .hasHighCardinalityKeyValue("repositoryId", "customer-consumer")
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    void recordErrorMarksTheSpanWithoutThrowing() {
        try (ObservabilityPort.SpanHandle span = observability.startSpan("validation", Map.of())) {
            span.recordError("build failed");
        }

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("validation")
                .that()
                .hasError();
    }

    @Test
    void nestedSpansAreParentedToTheCurrentlyOpenSpan() {
        try (ObservabilityPort.SpanHandle outer = observability.startSpan("analyse", Map.of())) {
            assertThat(observations.getCurrentObservation().getContext().getName()).isEqualTo("analyse");
            try (ObservabilityPort.SpanHandle inner = observability.startSpan("diff", Map.of())) {
                assertThat(observations.getCurrentObservation().getContext().getName()).isEqualTo("diff");
            }
            // Closing the inner span's scope must restore the outer span as current.
            assertThat(observations.getCurrentObservation().getContext().getName()).isEqualTo("analyse");
        }
        assertThat(observations.getCurrentObservation()).isNull();
    }

    @Test
    void llmUsageIsRecordedAsTokenDistributions() {
        observability.recordLlmUsage("migration-planner", 120, 45);

        assertThat(meters.get("contractguard.llm.tokens")
                .tag("prompt", "migration-planner").tag("type", "prompt")
                .summary().totalAmount()).isEqualTo(120.0);
        assertThat(meters.get("contractguard.llm.tokens")
                .tag("prompt", "migration-planner").tag("type", "completion")
                .summary().totalAmount()).isEqualTo(45.0);
    }

    @Test
    void negativeTokenCountsAreNotRecorded() {
        observability.recordLlmUsage("scripted", -1, -1);

        assertThat(meters.find("contractguard.llm.tokens").summary()).isNull();
    }
}
