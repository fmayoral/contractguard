package com.contractguard.application.service;

import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.PatchArtifact;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunStatisticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final RunStatisticsService service =
            new RunStatisticsService(runs, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void emptyHistoryYieldsZeroesAndADenseActivityWindow() {
        RunStatisticsService.Statistics stats = service.statistics();

        assertThat(stats.totalRuns()).isZero();
        assertThat(stats.activeRuns()).isZero();
        assertThat(stats.totalChanges()).isZero();
        assertThat(stats.changesByType()).isEmpty();
        assertThat(stats.remediation().validatedRuns()).isZero();
        assertThat(stats.remediation().averageValidationMillis()).isZero();
        // The window is dense even with no runs: 14 days ending today, all zero.
        assertThat(stats.runsPerDay()).hasSize(RunStatisticsService.ACTIVITY_WINDOW_DAYS);
        assertThat(stats.runsPerDay().getLast().day()).isEqualTo(NOW.atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(stats.runsPerDay()).allSatisfy(daily -> assertThat(daily.count()).isZero());
    }

    @Test
    void aggregatesStatesChangesAndActivityAcrossRuns() {
        // Fixtures hardcode id run-1; the second save needs a distinct ID or it overwrites the first.
        runs.save(Fixtures.runAwaitingApproval());
        runs.save(withId(Fixtures.runSucceeded(), "run-2"));

        RunStatisticsService.Statistics stats = service.statistics();

        assertThat(stats.totalRuns()).isEqualTo(2);
        assertThat(stats.activeRuns()).isEqualTo(1); // AWAITING_APPROVAL is non-terminal
        assertThat(stats.runsByState())
                .containsEntry(RunState.AWAITING_APPROVAL, 1)
                .containsEntry(RunState.SUCCEEDED, 1);
        assertThat(stats.totalChanges()).isEqualTo(2);
        assertThat(stats.changesByType()).containsEntry(ChangeType.PROPERTY_RENAMED, 2);
        assertThat(stats.changesByClassification()).containsEntry(Classification.BREAKING, 2);
        // Fixtures.T0 (2026-07-17) is within the 14-day window ending NOW (2026-07-23).
        assertThat(stats.runsPerDay().stream().mapToInt(RunStatisticsService.DailyCount::count).sum())
                .isEqualTo(2);
    }

    @Test
    void distributionsAreOrderedMostFrequentFirst() {
        AnalysisRun run = Fixtures.newRun();
        run.transitionTo(RunState.VALIDATING_INPUT, Fixtures.T0);
        run.transitionTo(RunState.DIFFING, Fixtures.T0);
        run.recordChanges(List.of(
                Fixtures.change("ch-1"),
                Fixtures.change("ch-2"),
                endpointAdded("ch-3")), Fixtures.T0);
        runs.save(run);

        RunStatisticsService.Statistics stats = service.statistics();

        assertThat(stats.changesByType().keySet())
                .containsExactly(ChangeType.PROPERTY_RENAMED, ChangeType.ENDPOINT_ADDED);
    }

    @Test
    void remediationCountsFirstPassRepairsAndAppliedPatchLines() {
        // One clean first-pass run (Fixtures: header-only applied patch, validation 1 ok).
        runs.save(Fixtures.runSucceeded());

        // One repaired run: first validation failed, second (repair) succeeded.
        AnalysisRun repaired = withId(Fixtures.runAwaitingApproval(), "run-2");
        String hash = repaired.plan().orElseThrow().hash();
        repaired.recordApproval(new com.contractguard.domain.Approval(
                repaired.id(), hash, com.contractguard.domain.Approval.Decision.APPROVED, Fixtures.T0), Fixtures.T0);
        repaired.transitionTo(RunState.PREPARING_BRANCH, Fixtures.T0);
        repaired.recordBranches("main", "contractguard/run-2", Fixtures.T0);
        repaired.transitionTo(RunState.PATCHING, Fixtures.T0);
        repaired.recordPatch(appliedPatch("p-a", "run-2", 1, "--- a/x\n+++ b/x\n+one\n+two\n-gone\n"), Fixtures.T0);
        repaired.transitionTo(RunState.VALIDATING, Fixtures.T0);
        repaired.recordValidation(Fixtures.validation(1, false), Fixtures.T0);
        repaired.transitionTo(RunState.REPAIRING, Fixtures.T0);
        repaired.recordPatch(appliedPatch("p-b", "run-2", 2, "--- a/y\n+++ b/y\n+fix\n"), Fixtures.T0);
        repaired.transitionTo(RunState.VALIDATING, Fixtures.T0);
        repaired.recordValidation(Fixtures.validation(2, true), Fixtures.T0);
        repaired.transitionTo(RunState.SUCCEEDED, Fixtures.T0);
        runs.save(repaired);

        RunStatisticsService.Remediation remediation = service.statistics().remediation();

        assertThat(remediation.validatedRuns()).isEqualTo(2);
        assertThat(remediation.firstPassRuns()).isEqualTo(1);
        assertThat(remediation.repairAttempts()).isEqualTo(1);
        assertThat(remediation.repairedRuns()).isEqualTo(1);
        // run-2's two applied patches: +3/-1 across x and y; runSucceeded's patch content
        // has no hunk lines, so it contributes paths but no line counts.
        assertThat(remediation.linesAdded()).isEqualTo(3);
        assertThat(remediation.linesRemoved()).isEqualTo(1);
        assertThat(remediation.filesTouched()).isEqualTo(3); // App.java (fixture), x, y
        assertThat(remediation.averageValidationMillis()).isEqualTo(30_000); // all fixtures run 30s
    }

    @Test
    void rejectedPatchesContributeNothing() {
        AnalysisRun run = withId(Fixtures.runAwaitingApproval(), "run-3");
        String hash = run.plan().orElseThrow().hash();
        run.recordApproval(new com.contractguard.domain.Approval(
                run.id(), hash, com.contractguard.domain.Approval.Decision.APPROVED, Fixtures.T0), Fixtures.T0);
        run.transitionTo(RunState.PREPARING_BRANCH, Fixtures.T0);
        run.recordBranches("main", "contractguard/run-3", Fixtures.T0);
        run.transitionTo(RunState.PATCHING, Fixtures.T0);
        run.recordPatch(new PatchArtifact("p-r", "run-3", 1, "+never-landed\n",
                List.of("src/x"), PatchArtifact.CheckStatus.REJECTED, null), Fixtures.T0);
        runs.save(run);

        RunStatisticsService.Remediation remediation = service.statistics().remediation();

        assertThat(remediation.linesAdded()).isZero();
        assertThat(remediation.filesTouched()).isZero();
    }

    private static PatchArtifact appliedPatch(String id, String runId, int attempt, String diff) {
        return new PatchArtifact(id, runId, attempt, diff,
                List.of(id.endsWith("a") ? "x" : "y"), PatchArtifact.CheckStatus.APPLIED, Fixtures.T0);
    }

    private static com.contractguard.domain.ApiChange endpointAdded(String id) {
        return new com.contractguard.domain.ApiChange(id, ChangeType.ENDPOINT_ADDED,
                Classification.NON_BREAKING, "GET", "/new", null, null, null, "/new",
                "NEW_ENDPOINT_NO_EXISTING_CALLERS", "{}", null);
    }

    /** Fixtures hardcode run-1; statistics need distinct IDs so the in-memory store keeps both. */
    private static AnalysisRun withId(AnalysisRun run, String id) {
        return AnalysisRun.rehydrate(id, run.name(), run.repositoryId(), run.traceId(),
                run.createdAt(), run.updatedAt(), run.state(), run.oldSpecFile(), run.newSpecFile(),
                run.oldSpecName(), run.newSpecName(), run.oldSpecHash(), run.newSpecHash(),
                run.originalBranch(), run.workingBranch(), run.failure().orElse(null),
                run.pullRequestUrl().orElse(null), run.changes(), run.evidence(), run.assessments(),
                run.plan().orElse(null), run.approval().orElse(null), run.patches(), run.validations());
    }
}
