package com.contractguard.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Shared builders for domain tests. */
final class Fixtures {

    static final Instant T0 = Instant.parse("2026-07-17T10:00:00Z");

    private Fixtures() {
    }

    static AnalysisRun newRun() {
        return new AnalysisRun("run-1", "demo", "customer-consumer", "trace-1", T0);
    }

    static ApiChange change(String id) {
        return new ApiChange(id, ChangeType.PROPERTY_RENAMED, Classification.BREAKING,
                null, null, "Customer", "fullName", "fullName", "displayName",
                "PROPERTY_RENAMED_BREAKING", "{\"paired\":true}", null);
    }

    static ImpactEvidence evidence(String id, String changeId) {
        return new ImpactEvidence(id, changeId, "src/main/java/App.java", 10, 12,
                "String fullName;", "fullName", "READS_RENAMED_PROPERTY", "abc123");
    }

    static ImpactAssessment assessment(String id, String changeId, List<String> evidenceIds) {
        return new ImpactAssessment(id, changeId, "CustomerDto", Severity.HIGH, Confidence.HIGH,
                "Deserialisation returns null fullName", "Rename field to displayName",
                List.of("Jackson maps by exact name"), evidenceIds);
    }

    static PlanItem planItem(String id) {
        return new PlanItem(id, "Rename DTO field", List.of("src/main/java/App.java"),
                "Rename fullName to displayName", List.of("src/test/java/AppTest.java"),
                "maven-verify", "low", "Revert branch", List.of("ev-1"));
    }

    static MigrationPlan plan(List<PlanItem> items) {
        return new MigrationPlan("plan-1", 1, PlanHasher.hash(items), items, T0);
    }

    static ValidationResult validation(int attempt, boolean successful) {
        return new ValidationResult(attempt, "maven-verify", successful ? 0 : 1, T0,
                Duration.ofSeconds(30), successful ? "BUILD SUCCESS" : "BUILD FAILURE", "log-1", successful);
    }

    static PatchArtifact patch(String id, int attempt) {
        return new PatchArtifact(id, "run-1", attempt, "--- a/x\n+++ b/x\n",
                List.of("src/main/java/App.java"), PatchArtifact.CheckStatus.VALID, null);
    }

    /** Drives a fresh run to AWAITING_APPROVAL with one change, one evidence, one assessment and a plan. */
    static AnalysisRun runAwaitingApproval() {
        AnalysisRun run = newRun();
        run.transitionTo(RunState.VALIDATING_INPUT, T0);
        run.transitionTo(RunState.DIFFING, T0);
        run.recordChanges(List.of(change("ch-1")), T0);
        run.transitionTo(RunState.SEARCHING, T0);
        run.recordEvidence(List.of(evidence("ev-1", "ch-1")), T0);
        run.transitionTo(RunState.ASSESSING, T0);
        run.recordAssessments(List.of(assessment("as-1", "ch-1", List.of("ev-1"))), T0);
        run.transitionTo(RunState.PLANNING, T0);
        run.attachPlan(plan(List.of(planItem("it-1"))), T0);
        run.transitionTo(RunState.AWAITING_APPROVAL, T0);
        return run;
    }
}
