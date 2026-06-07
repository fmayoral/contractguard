package com.contractguard.domain;

import java.util.Objects;

/**
 * User-facing description of a failure: what failed, whether the repository
 * was mutated, and where to look next (§19).
 *
 * @param artifactId optional log/artifact reference, null when none exists
 */
public record RunFailure(
        FailureCategory category,
        String message,
        boolean mutationOccurred,
        String artifactId,
        String remediation) {

    public RunFailure {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(remediation, "remediation");
    }
}
