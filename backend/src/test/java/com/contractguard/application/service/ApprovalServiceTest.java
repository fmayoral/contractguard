package com.contractguard.application.service;

import com.contractguard.adapter.notification.NoOpNotificationPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.AuditEventType;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryAuditTrail;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalServiceTest {

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private InMemoryAuditTrail audit;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        audit = new InMemoryAuditTrail();
        service = new ApprovalService(runs, events,
                new AuditTrailService(audit, new NoOpNotificationPort(), "test-operator", Clock.systemUTC()),
                Clock.systemUTC());
    }

    private AnalysisRun awaitingApproval() {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        runs.save(run);
        return run;
    }

    @Test
    void approvalIsRecordedAgainstThePlanHash() {
        AnalysisRun run = awaitingApproval();
        String hash = run.plan().orElseThrow().hash();

        AnalysisRun decided = service.decide(run.id(), Approval.Decision.APPROVED, hash);

        assertThat(decided.isApproved()).isTrue();
        assertThat(decided.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(events.all()).anySatisfy(event -> {
            assertThat(event.step()).isEqualTo("approval");
            assertThat(event.status()).isEqualTo("APPROVED");
        });
        assertThat(audit.findByRun(run.id())).anySatisfy(entry -> {
            assertThat(entry.eventType()).isEqualTo(AuditEventType.APPROVAL_DECISION);
            assertThat(entry.principal()).isEqualTo("test-operator");
            assertThat(entry.planHash()).isEqualTo(hash);
        });
    }

    @Test
    void rejectionTerminatesTheRunWithoutModification() {
        AnalysisRun run = awaitingApproval();
        String hash = run.plan().orElseThrow().hash();

        AnalysisRun decided = service.decide(run.id(), Approval.Decision.REJECTED, hash);

        assertThat(decided.state()).isEqualTo(RunState.REJECTED);
        assertThat(decided.isApproved()).isFalse();
    }

    @Test
    void staleHashIsRejectedTyped() {
        AnalysisRun run = awaitingApproval();
        String runId = run.id();

        assertThatThrownBy(() -> service.decide(runId, Approval.Decision.APPROVED, "stale"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.APPROVAL_MISMATCH));
        assertThat(runs.findById(runId).orElseThrow().isApproved()).isFalse();
    }

    @Test
    void decisionOutsideAwaitingApprovalIsRejected() {
        AnalysisRun run = Fixtures.newRun();
        runs.save(run);
        String runId = run.id();

        assertThatThrownBy(() -> service.decide(runId, Approval.Decision.APPROVED, "any"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.ILLEGAL_STATE));
    }

    @Test
    void unknownRunIsRejected() {
        assertThatThrownBy(() -> service.decide("ghost", Approval.Decision.APPROVED, "h"))
                .isInstanceOf(ContractGuardException.class);
    }
}
