package com.contractguard.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunStateTest {

    @Test
    void happyPathAnalysisChainIsLegal() {
        assertThat(RunState.CREATED.canTransitionTo(RunState.VALIDATING_INPUT)).isTrue();
        assertThat(RunState.VALIDATING_INPUT.canTransitionTo(RunState.DIFFING)).isTrue();
        assertThat(RunState.DIFFING.canTransitionTo(RunState.SEARCHING)).isTrue();
        assertThat(RunState.SEARCHING.canTransitionTo(RunState.ASSESSING)).isTrue();
        assertThat(RunState.ASSESSING.canTransitionTo(RunState.PLANNING)).isTrue();
        assertThat(RunState.PLANNING.canTransitionTo(RunState.AWAITING_APPROVAL)).isTrue();
    }

    @Test
    void approvalForksToRejectionOrExecution() {
        assertThat(RunState.AWAITING_APPROVAL.canTransitionTo(RunState.REJECTED)).isTrue();
        assertThat(RunState.AWAITING_APPROVAL.canTransitionTo(RunState.PREPARING_BRANCH)).isTrue();
        assertThat(RunState.AWAITING_APPROVAL.canTransitionTo(RunState.PATCHING)).isFalse();
    }

    @Test
    void executionChainIsLegal() {
        assertThat(RunState.PREPARING_BRANCH.canTransitionTo(RunState.PATCHING)).isTrue();
        assertThat(RunState.PATCHING.canTransitionTo(RunState.VALIDATING)).isTrue();
        assertThat(RunState.VALIDATING.canTransitionTo(RunState.SUCCEEDED)).isTrue();
        assertThat(RunState.VALIDATING.canTransitionTo(RunState.REPAIRING)).isTrue();
        assertThat(RunState.REPAIRING.canTransitionTo(RunState.VALIDATING)).isTrue();
    }

    @Test
    void skippingStepsIsIllegal() {
        assertThat(RunState.CREATED.canTransitionTo(RunState.DIFFING)).isFalse();
        assertThat(RunState.DIFFING.canTransitionTo(RunState.PLANNING)).isFalse();
        assertThat(RunState.PLANNING.canTransitionTo(RunState.SUCCEEDED)).isFalse();
        assertThat(RunState.REPAIRING.canTransitionTo(RunState.REPAIRING)).isFalse();
    }

    @Test
    void modificationStatesAreUnreachableWithoutApprovalGate() {
        // The only entry into PREPARING_BRANCH (and thus PATCHING) is AWAITING_APPROVAL.
        for (RunState state : RunState.values()) {
            if (state != RunState.AWAITING_APPROVAL) {
                assertThat(state.canTransitionTo(RunState.PREPARING_BRANCH))
                        .as("%s must not reach PREPARING_BRANCH", state)
                        .isFalse();
            }
        }
    }

    @Test
    void deadEndTerminalStatesHaveNoSuccessors() {
        for (RunState terminal : new RunState[] {
                RunState.FAILED, RunState.REJECTED, RunState.CANCELLED, RunState.PUBLISHED}) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.successors()).isEmpty();
        }
    }

    @Test
    void succeededAndPublishFailedAreTerminalButRemainPublishable() {
        // FR-027: publishing is a distinct, explicit action a human triggers after
        // the pipeline already finished — never automatic, and retryable on failure.
        for (RunState state : new RunState[] {RunState.SUCCEEDED, RunState.PUBLISH_FAILED}) {
            assertThat(state.isTerminal()).isTrue();
            assertThat(state.canTransitionTo(RunState.PUBLISHING)).isTrue();
        }
    }

    @Test
    void activeStatesCanAlwaysFailOrCancel() {
        for (RunState state : RunState.values()) {
            if (!state.isTerminal()) {
                assertThat(state.canTransitionTo(RunState.FAILED)).as("%s -> FAILED", state).isTrue();
                assertThat(state.canTransitionTo(RunState.CANCELLED)).as("%s -> CANCELLED", state).isTrue();
            }
        }
    }

    @Test
    void onlyTheStatesAHumanMustActOnOrLearnAboutNeedAttention() {
        // Drives both the in-app toast and the outbound webhook (FR-031) off one set: the plan
        // gate, plus every way a run can finish that isn't the user's own REJECTED/CANCELLED/
        // PUBLISHED action a moment before.
        for (RunState state : new RunState[] {
                RunState.AWAITING_APPROVAL, RunState.SUCCEEDED, RunState.FAILED, RunState.PUBLISH_FAILED}) {
            assertThat(state.needsHumanAttention()).as("%s needs attention", state).isTrue();
        }
        for (RunState state : RunState.values()) {
            if (state != RunState.AWAITING_APPROVAL && state != RunState.SUCCEEDED
                    && state != RunState.FAILED && state != RunState.PUBLISH_FAILED) {
                assertThat(state.needsHumanAttention()).as("%s does not need attention", state).isFalse();
            }
        }
    }
}
