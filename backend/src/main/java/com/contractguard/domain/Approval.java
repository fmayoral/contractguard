package com.contractguard.domain;

import java.time.Instant;
import java.util.Objects;

/** Recorded human decision over an exact plan hash (FR-010). */
public record Approval(String runId, String planHash, Decision decision, Instant decidedAt) {

    public enum Decision {
        APPROVED,
        REJECTED
    }

    public Approval {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }
}
