package com.contractguard.application.llm.contract;

import java.util.List;

/** Schema for one impact assessment produced by the investigator agent. */
public record AssessmentDraft(
        String apiChangeId,
        String component,
        String severity,
        String confidence,
        String failureMode,
        String recommendedAction,
        List<String> assumptions,
        List<String> evidenceIds) {
}
