package com.contractguard.application.llm.contract;

import java.util.List;

/** Schema for migration-planner output (maps to {@link com.contractguard.domain.PlanItem}). */
public record PlanDraft(List<Item> items) {

    public record Item(
            String objective,
            List<String> expectedFiles,
            String proposedAction,
            List<String> testsToUpdate,
            String validationCommand,
            String risk,
            String rollback,
            List<String> evidenceIds) {
    }
}
