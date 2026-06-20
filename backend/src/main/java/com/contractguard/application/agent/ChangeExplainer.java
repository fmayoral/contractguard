package com.contractguard.application.agent;

import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.llm.contract.ExplanationsResponse;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.domain.ApiChange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM node explaining classified changes (§9 node 3). The model receives the
 * deterministic facts and returns prose; it can never add, drop or reclassify
 * a change — the validator requires exactly one explanation per change ID.
 */
public class ChangeExplainer {

    private final LlmJsonClient client;
    private final PromptLibrary prompts;
    private final JsonCodec codec;

    public ChangeExplainer(LlmJsonClient client, PromptLibrary prompts, JsonCodec codec) {
        this.client = client;
        this.prompts = prompts;
        this.codec = codec;
    }

    /** @return explanation text per change ID */
    public Map<String, String> explain(List<ApiChange> changes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changes", AgentPayloads.changes(changes));
        Set<String> expectedIds = changes.stream().map(ApiChange::id).collect(Collectors.toSet());

        ExplanationsResponse response = client.request(
                prompts.get("change-explainer", "v1"),
                codec.encode(payload),
                ExplanationsResponse.class,
                r -> validate(r, expectedIds));

        Map<String, String> explanations = new LinkedHashMap<>();
        for (ExplanationsResponse.Item item : response.explanations()) {
            String text = item.uncertainty() == null || item.uncertainty().isBlank()
                    ? item.explanation()
                    : item.explanation() + " Uncertainty: " + item.uncertainty();
            explanations.put(item.changeId(), text);
        }
        return explanations;
    }

    private List<String> validate(ExplanationsResponse response, Set<String> expectedIds) {
        List<String> violations = new ArrayList<>();
        if (response.explanations() == null) {
            return List.of("'explanations' array is missing");
        }
        Set<String> seen = new HashSet<>();
        for (ExplanationsResponse.Item item : response.explanations()) {
            if (item.changeId() == null || !expectedIds.contains(item.changeId())) {
                violations.add("unknown changeId '%s'".formatted(item.changeId()));
            } else if (!seen.add(item.changeId())) {
                violations.add("duplicate changeId '%s'".formatted(item.changeId()));
            }
            if (item.explanation() == null || item.explanation().isBlank()) {
                violations.add("empty explanation for '%s'".formatted(item.changeId()));
            }
        }
        for (String id : expectedIds) {
            if (!seen.contains(id)) {
                violations.add("missing explanation for change '%s'".formatted(id));
            }
        }
        return violations;
    }
}
