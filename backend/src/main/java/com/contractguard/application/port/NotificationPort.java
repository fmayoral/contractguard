package com.contractguard.application.port;

import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.RunState;

/**
 * Outbound alerting for runs that need a human to look at them (FR-031): the same states the
 * in-app toast reacts to ({@link RunState#needsHumanAttention()}). Implementations must never
 * throw — a notification failure is never allowed to fail the state transition that triggered
 * it. {@link com.contractguard.application.service.AuditTrailService#recordTransition} is the
 * single caller.
 */
public interface NotificationPort {

    void notify(AnalysisRun run, RunState state);
}
