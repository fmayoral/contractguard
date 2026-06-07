package com.contractguard.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Outcome of one allow-listed validation command execution (FR-016). */
public record ValidationResult(
        int attempt,
        String command,
        int exitCode,
        Instant startedAt,
        Duration duration,
        String summary,
        String outputArtifactId,
        boolean successful) {

    public ValidationResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(summary, "summary");
        if (attempt < 1 || attempt > 2) {
            throw new IllegalArgumentException("validation attempt must be 1 or 2, was " + attempt);
        }
        if (successful && exitCode != 0) {
            throw new IllegalArgumentException("successful validation cannot have exit code " + exitCode);
        }
    }
}
