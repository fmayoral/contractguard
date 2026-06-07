package com.contractguard.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The complete migration proposal awaiting approval. The {@code hash} is
 * computed from the canonical plan content; approval is only valid against
 * the exact hash, so any plan change invalidates a prior approval (FR-010).
 */
public record MigrationPlan(
        String id,
        int version,
        String hash,
        List<PlanItem> items,
        Instant createdAt) {

    public MigrationPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(hash, "hash");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(createdAt, "createdAt");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a migration plan must contain at least one item");
        }
        if (version < 1) {
            throw new IllegalArgumentException("plan version must be >= 1");
        }
    }

    /** Union of every file the plan is allowed to touch, sorted for determinism. */
    public Set<String> approvedFiles() {
        Set<String> files = new TreeSet<>();
        for (PlanItem item : items) {
            files.addAll(item.expectedFiles());
            files.addAll(item.testsToUpdate());
        }
        return files;
    }
}
