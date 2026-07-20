package com.contractguard.application.service;

import com.contractguard.domain.AuditEventType;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryAuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTrailServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryAuditTrail port;
    private AuditTrailService service;

    @BeforeEach
    void setUp() {
        port = new InMemoryAuditTrail();
        service = new AuditTrailService(port, "test-operator", CLOCK);
    }

    @Test
    void recordsAStateTransitionUnderTheConfiguredPrincipal() {
        var run = Fixtures.newRun();

        service.recordTransition(run, RunState.CREATED, RunState.VALIDATING_INPUT);

        assertThat(service.findByRun(run.id())).singleElement().satisfies(entry -> {
            assertThat(entry.eventType()).isEqualTo(AuditEventType.STATE_TRANSITION);
            assertThat(entry.principal()).isEqualTo("test-operator");
            assertThat(entry.runId()).isEqualTo(run.id());
            assertThat(entry.repositoryId()).isEqualTo(run.repositoryId());
            assertThat(entry.detail()).isEqualTo("CREATED -> VALIDATING_INPUT");
            assertThat(entry.planHash()).isNull();
            assertThat(entry.occurredAt()).isEqualTo(CLOCK.instant());
        });
    }

    @Test
    void recordsAnApprovalDecisionWithThePlanHash() {
        var run = Fixtures.newRun();

        service.recordApprovalDecision(run, "APPROVED", "hash-123");

        assertThat(service.findByRun(run.id())).singleElement().satisfies(entry -> {
            assertThat(entry.eventType()).isEqualTo(AuditEventType.APPROVAL_DECISION);
            assertThat(entry.planHash()).isEqualTo("hash-123");
            assertThat(entry.detail()).isEqualTo("Plan approved");
        });
    }

    @Test
    void recordsARepositoryMutation() {
        var run = Fixtures.newRun();

        service.recordRepositoryMutation(run, "Branch contractguard/run-abc created from main");

        assertThat(service.findByRun(run.id())).singleElement().satisfies(entry -> {
            assertThat(entry.eventType()).isEqualTo(AuditEventType.REPOSITORY_MUTATION);
            assertThat(entry.detail()).isEqualTo("Branch contractguard/run-abc created from main");
        });
    }

    @Test
    void eachEntryGetsAUniqueId() {
        var run = Fixtures.newRun();

        service.recordTransition(run, RunState.CREATED, RunState.VALIDATING_INPUT);
        service.recordTransition(run, RunState.VALIDATING_INPUT, RunState.DIFFING);

        assertThat(service.findByRun(run.id())).extracting("id").doesNotHaveDuplicates();
    }

    @Test
    void findByRepositoryAndFindAllDelegateToThePort() {
        var run = Fixtures.newRun();
        service.recordTransition(run, RunState.CREATED, RunState.VALIDATING_INPUT);

        assertThat(service.findByRepository(run.repositoryId())).hasSize(1);
        assertThat(service.findAll()).hasSize(1);
        assertThat(port.findAll()).hasSize(1);
    }
}
