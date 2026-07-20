package com.contractguard.application.service;

import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.RunState;

import java.time.Clock;

/**
 * The human approval gate (§9 node 6, FR-010). Enforced entirely in backend
 * state: the decision is only accepted in AWAITING_APPROVAL, only against the
 * exact current plan hash, and a rejection terminates the run untouched.
 */
public class ApprovalService {

    private final RunRepository runs;
    private final RunEventLog events;
    private final AuditTrailService audit;
    private final Clock clock;

    public ApprovalService(RunRepository runs, RunEventLog events, AuditTrailService audit, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public AnalysisRun decide(String runId, Approval.Decision decision, String planHash) {
        AnalysisRun run = RunLookup.require(runs, runId);
        run.recordApproval(new Approval(runId, planHash, decision, clock.instant()), clock.instant());
        if (decision == Approval.Decision.REJECTED) {
            run.transitionTo(RunState.REJECTED, clock.instant());
        }
        runs.save(run);
        events.append(runId, "approval", decision.name(),
                decision == Approval.Decision.APPROVED
                        ? "Plan approved (hash %s); execution may start".formatted(shortHash(planHash))
                        : "Plan rejected; the repository was not modified",
                "{\"kind\":\"human\"}");
        audit.recordApprovalDecision(run, decision.name(), planHash);
        return run;
    }

    private static String shortHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }
}
