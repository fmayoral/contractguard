package com.contractguard.domain;

import java.util.List;
import java.util.Objects;

/** One actionable step of a {@link MigrationPlan} (FR-009). */
public record PlanItem(
        String id,
        String objective,
        List<String> expectedFiles,
        String proposedAction,
        List<String> testsToUpdate,
        String validationCommand,
        String risk,
        String rollback,
        List<String> evidenceIds) {

    public PlanItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(objective, "objective");
        expectedFiles = List.copyOf(Objects.requireNonNull(expectedFiles, "expectedFiles"));
        Objects.requireNonNull(proposedAction, "proposedAction");
        testsToUpdate = List.copyOf(Objects.requireNonNull(testsToUpdate, "testsToUpdate"));
        Objects.requireNonNull(validationCommand, "validationCommand");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(rollback, "rollback");
        evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        if (expectedFiles.isEmpty()) {
            throw new IllegalArgumentException("plan item %s names no files".formatted(id));
        }
    }
}
