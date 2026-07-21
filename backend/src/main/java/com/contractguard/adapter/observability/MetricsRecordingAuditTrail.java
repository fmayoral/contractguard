package com.contractguard.adapter.observability;

import com.contractguard.application.port.AuditTrailPort;
import com.contractguard.domain.AuditEntry;
import com.contractguard.domain.AuditEventType;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;

/**
 * Decorates the audit trail with a Prometheus counter of run-state transitions (FR-029), reusing
 * the audit log as the single existing chokepoint for "a state transition happened" rather than
 * threading a metrics call through every service that already calls
 * {@code AuditTrailService.recordTransition}. {@code to_state} is a small closed enum, so it is
 * safe as a metric label — unlike a run ID, its cardinality cannot grow.
 */
public class MetricsRecordingAuditTrail implements AuditTrailPort {

    private final AuditTrailPort delegate;
    private final MeterRegistry meters;

    public MetricsRecordingAuditTrail(AuditTrailPort delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    @Override
    public void record(AuditEntry entry) {
        delegate.record(entry);
        if (entry.eventType() == AuditEventType.STATE_TRANSITION) {
            meters.counter("contractguard.runs.transitions", "to_state", toState(entry.detail())).increment();
        }
    }

    /** {@code detail} is always {@code "FROM -> TO"}, written by the sole producer, AuditTrailService. */
    private static String toState(String detail) {
        int arrow = detail.lastIndexOf("-> ");
        return arrow < 0 ? "unknown" : detail.substring(arrow + 3);
    }

    @Override
    public List<AuditEntry> findByRun(String runId) {
        return delegate.findByRun(runId);
    }

    @Override
    public List<AuditEntry> findByRepository(String repositoryId) {
        return delegate.findByRepository(repositoryId);
    }

    @Override
    public List<AuditEntry> findAll() {
        return delegate.findAll();
    }
}
