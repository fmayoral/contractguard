package com.contractguard.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Workflow states of an {@link AnalysisRun}. The transition table below is the
 * single authority on which moves are legal; {@link AnalysisRun#transitionTo}
 * consults it, making invalid transitions unrepresentable at runtime.
 */
public enum RunState {
    CREATED,
    VALIDATING_INPUT,
    DIFFING,
    SEARCHING,
    ASSESSING,
    PLANNING,
    AWAITING_APPROVAL,
    REJECTED,
    PREPARING_BRANCH,
    PATCHING,
    VALIDATING,
    REPAIRING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Set<RunState> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, REJECTED, CANCELLED);

    public Set<RunState> successors() {
        return switch (this) {
            case CREATED -> withAbort(VALIDATING_INPUT);
            case VALIDATING_INPUT -> withAbort(DIFFING);
            case DIFFING -> withAbort(SEARCHING);
            case SEARCHING -> withAbort(ASSESSING);
            case ASSESSING -> withAbort(PLANNING);
            case PLANNING -> withAbort(AWAITING_APPROVAL);
            case AWAITING_APPROVAL -> withAbort(REJECTED, PREPARING_BRANCH);
            case PREPARING_BRANCH -> withAbort(PATCHING);
            case PATCHING -> withAbort(VALIDATING);
            case VALIDATING -> withAbort(SUCCEEDED, REPAIRING);
            case REPAIRING -> withAbort(VALIDATING);
            case SUCCEEDED, FAILED, REJECTED, CANCELLED -> EnumSet.noneOf(RunState.class);
        };
    }

    public boolean canTransitionTo(RunState target) {
        return successors().contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    private static Set<RunState> withAbort(RunState... regular) {
        Set<RunState> set = EnumSet.of(FAILED, CANCELLED);
        for (RunState state : regular) {
            set.add(state);
        }
        return set;
    }
}
