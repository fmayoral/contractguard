package com.contractguard.application.service;

import com.contractguard.application.port.AuditTrailPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.AuditEntry;
import com.contractguard.domain.AuditEventType;
import com.contractguard.domain.Ids;
import com.contractguard.domain.RunState;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Records the three fact categories FR-025 requires — state transitions,
 * approval decisions and repository mutations — under a single configured
 * principal. Every other service calls this instead of {@link AuditTrailPort}
 * directly, so the principal and ID generation live in exactly one place.
 *
 * <p>There is no authenticated caller yet (FR-024 is unbuilt), so
 * {@code principal} is a fixed, operator-configured placeholder rather than a
 * per-request identity — see ADR-0008.
 */
public class AuditTrailService {

    private final AuditTrailPort port;
    private final String principal;
    private final Clock clock;

    public AuditTrailService(AuditTrailPort port, String principal, Clock clock) {
        this.port = port;
        this.principal = principal;
        this.clock = clock;
    }

    public void recordTransition(AnalysisRun run, RunState from, RunState to) {
        port.record(new AuditEntry(Ids.newId(), run.id(), run.repositoryId(), principal,
                AuditEventType.STATE_TRANSITION, "%s -> %s".formatted(from, to), null, clock.instant()));
    }

    public void recordApprovalDecision(AnalysisRun run, String decision, String planHash) {
        port.record(new AuditEntry(Ids.newId(), run.id(), run.repositoryId(), principal,
                AuditEventType.APPROVAL_DECISION, "Plan " + decision.toLowerCase(Locale.ROOT),
                planHash, clock.instant()));
    }

    public void recordRepositoryMutation(AnalysisRun run, String detail) {
        port.record(new AuditEntry(Ids.newId(), run.id(), run.repositoryId(), principal,
                AuditEventType.REPOSITORY_MUTATION, detail, null, clock.instant()));
    }

    public List<AuditEntry> findByRun(String runId) {
        return port.findByRun(runId);
    }

    public List<AuditEntry> findByRepository(String repositoryId) {
        return port.findByRepository(repositoryId);
    }

    public List<AuditEntry> findAll() {
        return port.findAll();
    }
}
