package com.contractguard.application.agent;

import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.llm.contract.FileRewrites;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.PlanItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation and repair agent (§9 nodes 7 and 10). The model receives the
 * approved plan and the complete contents of approved files, and returns new
 * file contents; any path outside the provided set is a validation failure —
 * scope can never expand silently (FR-014.4).
 */
public class ImplementationAgent {

    public static final String IMPLEMENTATION_PROMPT = "implementation-agent";
    public static final String REPAIR_PROMPT = "repair-agent";

    private final LlmJsonClient client;
    private final PromptLibrary prompts;
    private final JsonCodec codec;

    public ImplementationAgent(LlmJsonClient client, PromptLibrary prompts, JsonCodec codec) {
        this.client = client;
        this.prompts = prompts;
        this.codec = codec;
    }

    /**
     * @param currentFiles              approved files' content, with any deterministic mechanical
     *                                  fix already applied (ADR-0015) — this is the baseline the
     *                                  model edits, not necessarily the untouched on-disk content
     * @param failureOutput             bounded, redacted build failure output; null for
     *                                  the first implementation attempt
     * @param deterministicChangesMade  whether the deterministic pre-transform already changed at
     *                                  least one approved file; when true, the model may legitimately
     *                                  propose no further changes (the plan may already be fully
     *                                  solved mechanically) instead of being forced to invent one
     * @return proposed new content per approved file (only changed files)
     */
    public Map<String, String> propose(String promptName, List<ApiChange> changes, MigrationPlan plan,
            Map<String, String> currentFiles, String failureOutput, boolean deterministicChangesMade) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changes", AgentPayloads.changes(changes));
        payload.put("planItems", planItems(plan));
        payload.put("files", files(currentFiles));
        if (failureOutput != null) {
            payload.put("failureOutput", failureOutput);
        }

        FileRewrites rewrites = client.request(
                prompts.get(promptName, "v1"),
                codec.encode(payload),
                FileRewrites.class,
                r -> validate(r, currentFiles, deterministicChangesMade));

        Map<String, String> result = new LinkedHashMap<>();
        for (FileRewrites.FileRewrite rewrite : rewrites.files()) {
            result.put(rewrite.path(), rewrite.newContent());
        }
        return result;
    }

    private List<String> validate(FileRewrites rewrites, Map<String, String> currentFiles,
            boolean deterministicChangesMade) {
        if (rewrites.files() == null) {
            return List.of("'files' array is missing");
        }
        List<String> violations = new ArrayList<>();
        if (rewrites.files().isEmpty() && !deterministicChangesMade) {
            violations.add("no file changes proposed; the approved plan requires modifications");
        }
        for (FileRewrites.FileRewrite rewrite : rewrites.files()) {
            if (rewrite.path() == null || !currentFiles.containsKey(rewrite.path())) {
                violations.add("file '%s' is not in the approved set".formatted(rewrite.path()));
            }
            if (rewrite.newContent() == null || rewrite.newContent().isBlank()) {
                violations.add("empty content proposed for '%s'".formatted(rewrite.path()));
            }
        }
        return violations;
    }

    private List<Map<String, Object>> planItems(MigrationPlan plan) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("objective", item.objective());
            map.put("expectedFiles", item.expectedFiles());
            map.put("proposedAction", item.proposedAction());
            map.put("testsToUpdate", item.testsToUpdate());
            items.add(map);
        }
        return items;
    }

    private List<Map<String, String>> files(Map<String, String> currentFiles) {
        List<Map<String, String>> files = new ArrayList<>();
        currentFiles.forEach((path, content) -> {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("content", content);
            files.add(map);
        });
        return files;
    }
}
