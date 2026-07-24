package com.contractguard.adapter.observability;

import com.contractguard.domain.AuditEntry;
import com.contractguard.domain.AuditEventType;
import com.contractguard.testsupport.InMemoryAuditTrail;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsRecordingAuditTrailTest {

    private final InMemoryAuditTrail delegate = new InMemoryAuditTrail();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final MetricsRecordingAuditTrail trail = new MetricsRecordingAuditTrail(delegate, meters);

    @Test
    void stateTransitionsIncrementACounterTaggedByTargetState() {
        trail.append(new AuditEntry("id-1", "run-1", "customer-consumer", "local-operator",
                AuditEventType.STATE_TRANSITION, "PLANNING -> AWAITING_APPROVAL", null, Instant.now()));

        assertThat(meters.get("contractguard.runs.transitions")
                .tag("to_state", "AWAITING_APPROVAL").counter().count()).isEqualTo(1.0);
        assertThat(delegate.findByRun("run-1")).hasSize(1);
    }

    @Test
    void nonTransitionEventsAreDelegatedWithoutIncrementingTheCounter() {
        trail.append(new AuditEntry("id-1", "run-1", "customer-consumer", "local-operator",
                AuditEventType.REPOSITORY_MUTATION, "Branch created", null, Instant.now()));

        assertThat(meters.find("contractguard.runs.transitions").counter()).isNull();
        assertThat(delegate.findByRun("run-1")).hasSize(1);
    }

    @Test
    void findMethodsDelegateToTheWrappedTrail() {
        AuditEntry entry = new AuditEntry("id-1", "run-1", "customer-consumer", "local-operator",
                AuditEventType.APPROVAL_DECISION, "Plan approved", "hash-1", Instant.now());
        trail.append(entry);

        assertThat(trail.findByRun("run-1")).containsExactly(entry);
        assertThat(trail.findByRepository("customer-consumer")).containsExactly(entry);
        assertThat(trail.findAll()).containsExactly(entry);
    }
}
