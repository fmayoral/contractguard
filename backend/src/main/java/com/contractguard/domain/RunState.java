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
    CANCELLED,
    PUBLISHING,
    PUBLISHED,
    PUBLISH_FAILED;

    private static final Set<RunState> TERMINAL =
            EnumSet.of(SUCCEEDED, FAILED, REJECTED, CANCELLED, PUBLISHED, PUBLISH_FAILED);

    /**
     * States where a human needs to look at the run: decide a plan, publish (or retry
     * publishing) a success, or understand a failure. Every other state either progresses on
     * its own or is an outcome the acting user already knows about because they just caused it
     * (REJECTED/CANCELLED/PUBLISHED). Drives both the in-app notification and the outbound
     * webhook (FR-031) off one authoritative set.
     */
    private static final Set<RunState> NEEDS_ATTENTION =
            EnumSet.of(AWAITING_APPROVAL, SUCCEEDED, FAILED, PUBLISH_FAILED);

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
            // SUCCEEDED/PUBLISH_FAILED are terminal outcomes that remain publishable
            // on demand (FR-027); publishing is never automatic (no abort states here
            // since there is nothing left to abort — the run's analysis already finished).
            case SUCCEEDED, PUBLISH_FAILED -> EnumSet.of(PUBLISHING);
            case PUBLISHING -> withAbort(PUBLISHED, PUBLISH_FAILED);
            case FAILED, REJECTED, CANCELLED, PUBLISHED -> EnumSet.noneOf(RunState.class);
        };
    }

    public boolean canTransitionTo(RunState target) {
        return successors().contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean needsHumanAttention() {
        return NEEDS_ATTENTION.contains(this);
    }

    private static Set<RunState> withAbort(RunState... regular) {
        Set<RunState> set = EnumSet.of(FAILED, CANCELLED);
        for (RunState state : regular) {
            set.add(state);
        }
        return set;
    }
}
