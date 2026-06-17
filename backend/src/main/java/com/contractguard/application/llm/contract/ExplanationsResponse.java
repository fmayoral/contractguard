package com.contractguard.application.llm.contract;

import java.util.List;

/** Schema for change-explainer output: one explanation per change ID. */
public record ExplanationsResponse(List<Item> explanations) {

    /** @param uncertainty explicit statement of what the model is unsure about, may be empty */
    public record Item(String changeId, String explanation, String uncertainty) {
    }
}
