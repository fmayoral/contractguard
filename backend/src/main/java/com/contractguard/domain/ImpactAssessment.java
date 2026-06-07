package com.contractguard.domain;

import java.util.List;
import java.util.Objects;

/**
 * Agent-produced impact judgement for one change. Must cite evidence:
 * an assessment without evidence IDs is rejected at construction, enforcing
 * the "no source-impact claim without deterministic evidence" rule (FR-008).
 */
public record ImpactAssessment(
        String id,
        String apiChangeId,
        String component,
        Severity severity,
        Confidence confidence,
        String failureMode,
        String recommendedAction,
        List<String> assumptions,
        List<String> evidenceIds) {

    public ImpactAssessment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(apiChangeId, "apiChangeId");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(failureMode, "failureMode");
        Objects.requireNonNull(recommendedAction, "recommendedAction");
        assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
        evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "impact assessment for change %s cites no evidence".formatted(apiChangeId));
        }
    }
}
