package com.contractguard.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.contractguard.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisRunTest {

    @Nested
    class Transitions {

        @Test
        void illegalTransitionThrowsTypedFailure() {
            AnalysisRun run = Fixtures.newRun();
            assertThatThrownBy(() -> run.transitionTo(RunState.PATCHING, T0))
                    .isInstanceOf(ContractGuardException.class)
                    .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                            .isEqualTo(FailureCategory.ILLEGAL_STATE));
            assertThat(run.state()).isEqualTo(RunState.CREATED);
        }

        @Test
        void markFailedFromAnyActiveStateSetsFailure() {
            AnalysisRun run = Fixtures.newRun();
            RunFailure failure = new RunFailure(FailureCategory.DIFF_FAILURE, "boom", false, null, "retry");
            run.markFailed(failure, T0);
            assertThat(run.state()).isEqualTo(RunState.FAILED);
            assertThat(run.failure()).contains(failure);
        }

        @Test
        void markFailedOnTerminalRunIsRejected() {
            AnalysisRun run = Fixtures.newRun();
            run.markCancelled(T0);
            assertThatThrownBy(() -> run.markFailed(
                    new RunFailure(FailureCategory.INTERNAL_ERROR, "late", false, null, "none"), T0))
                    .isInstanceOf(ContractGuardException.class);
        }
    }

    @Nested
    class EvidenceRules {

        @Test
        void recordingChangesOutsideDiffingIsRejected() {
            AnalysisRun run = Fixtures.newRun();
            assertThatThrownBy(() -> run.recordChanges(List.of(Fixtures.change("ch-1")), T0))
                    .isInstanceOf(ContractGuardException.class);
        }

        @Test
        void assessmentsCitingUnknownEvidenceAreRejected() {
            AnalysisRun run = Fixtures.newRun();
            run.transitionTo(RunState.VALIDATING_INPUT, T0);
            run.transitionTo(RunState.DIFFING, T0);
            run.recordChanges(List.of(Fixtures.change("ch-1")), T0);
            run.transitionTo(RunState.SEARCHING, T0);
            run.recordEvidence(List.of(Fixtures.evidence("ev-1", "ch-1")), T0);
            run.transitionTo(RunState.ASSESSING, T0);

            List<ImpactAssessment> bogus =
                    List.of(Fixtures.assessment("as-1", "ch-1", List.of("ev-does-not-exist")));
            assertThatThrownBy(() -> run.recordAssessments(bogus, T0))
                    .isInstanceOf(ContractGuardException.class)
                    .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                            .isEqualTo(FailureCategory.POLICY_VIOLATION));
        }

        @Test
        void explanationAttachesToExistingChangeOnly() {
            AnalysisRun run = Fixtures.newRun();
            run.transitionTo(RunState.VALIDATING_INPUT, T0);
            run.transitionTo(RunState.DIFFING, T0);
            run.recordChanges(List.of(Fixtures.change("ch-1")), T0);
            run.attachExplanation("ch-1", "Consumers deserialising fullName will break.", T0);
            assertThat(run.changes().get(0).explanation()).contains("fullName");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> run.attachExplanation("nope", "x", T0));
        }
    }

    @Nested
    class ApprovalGate {

        @Test
        void approvalWithMatchingHashIsRecorded() {
            AnalysisRun run = Fixtures.runAwaitingApproval();
            String hash = run.plan().orElseThrow().hash();
            run.recordApproval(new Approval(run.id(), hash, Approval.Decision.APPROVED, T0), T0);
            assertThat(run.isApproved()).isTrue();
        }

        @Test
        void approvalWithStaleHashIsRejected() {
            AnalysisRun run = Fixtures.runAwaitingApproval();
            assertThatThrownBy(() -> run.recordApproval(
                    new Approval(run.id(), "stale-hash", Approval.Decision.APPROVED, T0), T0))
                    .isInstanceOf(ContractGuardException.class)
                    .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                            .isEqualTo(FailureCategory.APPROVAL_MISMATCH));
            assertThat(run.isApproved()).isFalse();
        }

        @Test
        void attachingANewPlanInvalidatesPriorApproval() {
            AnalysisRun approved = Fixtures.runAwaitingApproval();
            String hash = approved.plan().orElseThrow().hash();
            approved.recordApproval(new Approval(approved.id(), hash, Approval.Decision.APPROVED, T0), T0);
            assertThat(approved.isApproved()).isTrue();

            // Simulate a re-planned run: same aggregate back in PLANNING with the old approval attached.
            AnalysisRun replanned = AnalysisRun.rehydrate(approved.id(), approved.name(),
                    approved.repositoryId(), approved.traceId(), approved.createdAt(), approved.updatedAt(),
                    RunState.PLANNING, null, null, null, null, null, null, null, null,
                    approved.changes(), approved.evidence(), approved.assessments(),
                    approved.plan().orElseThrow(), approved.approval().orElseThrow(), List.of(), List.of());

            PlanItem differentItem = new PlanItem("it-2", "Different objective",
                    List.of("src/main/java/Other.java"), "Do something else", List.of(),
                    "maven-verify", "medium", "Revert branch", List.of("ev-1"));
            MigrationPlan newPlan = new MigrationPlan("plan-2", 2,
                    PlanHasher.hash(List.of(differentItem)), List.of(differentItem), T0);

            replanned.attachPlan(newPlan, T0);
            assertThat(replanned.approval()).isEmpty();
            assertThat(replanned.isApproved()).isFalse();
        }

        @Test
        void rejectionDoesNotApprove() {
            AnalysisRun run = Fixtures.runAwaitingApproval();
            String hash = run.plan().orElseThrow().hash();
            run.recordApproval(new Approval(run.id(), hash, Approval.Decision.REJECTED, T0), T0);
            assertThat(run.isApproved()).isFalse();
            run.transitionTo(RunState.REJECTED, T0);
            assertThat(run.state().isTerminal()).isTrue();
        }

        @Test
        void approvalBeforePlanningIsImpossible() {
            AnalysisRun run = Fixtures.newRun();
            assertThatThrownBy(() -> run.recordApproval(
                    new Approval(run.id(), "any", Approval.Decision.APPROVED, T0), T0))
                    .isInstanceOf(ContractGuardException.class);
        }
    }

    @Nested
    class RepairLimit {

        @Test
        void repairAllowedOnlyAfterSingleFailedValidation() {
            AnalysisRun run = approvedRunInValidating();
            run.recordValidation(Fixtures.validation(1, false), T0);
            assertThat(run.repairAllowed()).isTrue();

            run.transitionTo(RunState.REPAIRING, T0);
            run.recordPatch(Fixtures.patch("p-2", 2), T0);
            run.transitionTo(RunState.VALIDATING, T0);
            run.recordValidation(Fixtures.validation(2, false), T0);
            assertThat(run.repairAllowed()).isFalse();
        }

        @Test
        void repairNotAllowedAfterSuccess() {
            AnalysisRun run = approvedRunInValidating();
            run.recordValidation(Fixtures.validation(1, true), T0);
            assertThat(run.repairAllowed()).isFalse();
        }

        @Test
        void thirdValidationAttemptIsStructurallyImpossible() {
            AnalysisRun run = approvedRunInValidating();
            run.recordValidation(Fixtures.validation(1, false), T0);
            run.transitionTo(RunState.REPAIRING, T0);
            run.recordPatch(Fixtures.patch("p-2", 2), T0);
            run.transitionTo(RunState.VALIDATING, T0);
            run.recordValidation(Fixtures.validation(2, false), T0);
            assertThatThrownBy(() -> run.recordValidation(Fixtures.validation(2, false), T0))
                    .isInstanceOf(ContractGuardException.class)
                    .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                            .isEqualTo(FailureCategory.POLICY_VIOLATION));
        }

        @Test
        void patchAttemptsMustArriveInOrder() {
            AnalysisRun run = approvedRunInValidating();
            assertThatThrownBy(() -> {
                run.transitionTo(RunState.REPAIRING, T0);
                run.recordPatch(Fixtures.patch("p-9", 2), T0);
                run.recordPatch(Fixtures.patch("p-10", 2), T0);
            }).isInstanceOf(ContractGuardException.class);
        }

        private AnalysisRun approvedRunInValidating() {
            AnalysisRun run = Fixtures.runAwaitingApproval();
            String hash = run.plan().orElseThrow().hash();
            run.recordApproval(new Approval(run.id(), hash, Approval.Decision.APPROVED, T0), T0);
            run.transitionTo(RunState.PREPARING_BRANCH, T0);
            run.recordBranches("main", "contractguard/run-run", T0);
            run.transitionTo(RunState.PATCHING, T0);
            run.recordPatch(Fixtures.patch("p-1", 1), T0);
            run.markPatchApplied("p-1", T0);
            run.transitionTo(RunState.VALIDATING, T0);
            return run;
        }
    }

    @Nested
    class Rehydration {

        @Test
        void rehydrateRestoresFullState() {
            AnalysisRun original = Fixtures.runAwaitingApproval();
            AnalysisRun restored = AnalysisRun.rehydrate(original.id(), original.name(),
                    original.repositoryId(), original.traceId(), original.createdAt(), original.updatedAt(),
                    original.state(), "old.yaml", "new.yaml", "old-hash", "new-hash", null, null, null, null,
                    original.changes(), original.evidence(), original.assessments(),
                    original.plan().orElseThrow(), null, List.of(), List.of());
            assertThat(restored.state()).isEqualTo(RunState.AWAITING_APPROVAL);
            assertThat(restored.changes()).hasSize(1);
            assertThat(restored.plan()).isPresent();
        }
    }

    @Test
    void patchAppliedMarkerUpdatesStatus() {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        run.recordApproval(new Approval(run.id(),
                run.plan().orElseThrow().hash(), Approval.Decision.APPROVED, T0), T0);
        run.transitionTo(RunState.PREPARING_BRANCH, T0);
        run.recordBranches("main", "contractguard/run-x", T0);
        run.transitionTo(RunState.PATCHING, T0);
        run.recordPatch(Fixtures.patch("p-1", 1), T0);
        run.markPatchApplied("p-1", T0);
        assertThat(run.patches().get(0).checkStatus()).isEqualTo(PatchArtifact.CheckStatus.APPLIED);
        assertThatIllegalArgumentException().isThrownBy(() -> run.markPatchApplied("missing", T0));
    }

    @Nested
    class Publishing {

        @Test
        void succeededRunCanBePublished() {
            AnalysisRun run = Fixtures.runSucceeded();
            run.transitionTo(RunState.PUBLISHING, T0);
            run.recordPublished("https://github.com/acme/widgets/pull/7", T0);
            assertThat(run.state()).isEqualTo(RunState.PUBLISHED);
            assertThat(run.pullRequestUrl()).contains("https://github.com/acme/widgets/pull/7");
            assertThat(run.state().isTerminal()).isTrue();
        }

        @Test
        void publishFailureRecordsReasonAndAllowsRetry() {
            AnalysisRun run = Fixtures.runSucceeded();
            run.transitionTo(RunState.PUBLISHING, T0);
            RunFailure failure = new RunFailure(FailureCategory.PULL_REQUEST_FAILED, "boom", true, null, "retry");

            run.recordPublishFailure(failure, T0);

            assertThat(run.state()).isEqualTo(RunState.PUBLISH_FAILED);
            assertThat(run.failure()).contains(failure);
            assertThat(run.pullRequestUrl()).isEmpty();

            // Retryable: PUBLISH_FAILED can go back through PUBLISHING to a successful publish.
            run.transitionTo(RunState.PUBLISHING, T0);
            run.recordPublished("https://github.com/acme/widgets/pull/8", T0);
            assertThat(run.state()).isEqualTo(RunState.PUBLISHED);
            assertThat(run.failure()).isEmpty();
        }

        @Test
        void recordPublishedOutsidePublishingIsRejected() {
            AnalysisRun run = Fixtures.runSucceeded();
            assertThatThrownBy(() -> run.recordPublished("https://github.com/acme/widgets/pull/1", T0))
                    .isInstanceOf(ContractGuardException.class)
                    .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                            .isEqualTo(FailureCategory.ILLEGAL_STATE));
        }

        @Test
        void publishingCannotStartBeforeSuccess() {
            AnalysisRun run = Fixtures.runAwaitingApproval();
            assertThatThrownBy(() -> run.transitionTo(RunState.PUBLISHING, T0))
                    .isInstanceOf(ContractGuardException.class)
                    .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                            .isEqualTo(FailureCategory.ILLEGAL_STATE));
        }
    }
}
