package com.contractguard.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A generated unified diff, its safety-check outcome and application record. */
public record PatchArtifact(
        String id,
        String runId,
        int attempt,
        String unifiedDiff,
        List<String> changedPaths,
        CheckStatus checkStatus,
        Instant appliedAt) {

    public enum CheckStatus {
        VALID,
        REJECTED,
        APPLIED
    }

    public PatchArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(unifiedDiff, "unifiedDiff");
        changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        Objects.requireNonNull(checkStatus, "checkStatus");
        if (attempt < 1 || attempt > 2) {
            throw new IllegalArgumentException("patch attempt must be 1 or 2, was " + attempt);
        }
        if (checkStatus == CheckStatus.APPLIED && appliedAt == null) {
            throw new IllegalArgumentException("applied patch requires an appliedAt timestamp");
        }
    }

    public PatchArtifact asApplied(Instant when) {
        return new PatchArtifact(id, runId, attempt, unifiedDiff, changedPaths, CheckStatus.APPLIED, when);
    }
}
