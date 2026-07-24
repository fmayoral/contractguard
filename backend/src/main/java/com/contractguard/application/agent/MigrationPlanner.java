package com.contractguard.application.agent;

import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.llm.contract.PlanDraft;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Ids;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.PlanHasher;
import com.contractguard.domain.PlanItem;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * The planning agent (§9 node 5). The produced plan is the exact scope
 * contract for the later patch: only evidence-backed files may be listed,
 * only allow-listed validation commands may be referenced, and the plan hash
 * is what the human approves (FR-009/FR-010).
 */
public class MigrationPlanner {

    private final LlmJsonClient client;
    private final PromptLibrary prompts;
    private final JsonCodec codec;
    private final WorkspacePolicy workspacePolicy;
    private final List<String> allowedCommands;
    private final Clock clock;

    public MigrationPlanner(LlmJsonClient client, PromptLibrary prompts, JsonCodec codec,
            WorkspacePolicy workspacePolicy, List<String> allowedCommands, Clock clock) {
        this.client = client;
        this.prompts = prompts;
        this.codec = codec;
        this.workspacePolicy = workspacePolicy;
        this.allowedCommands = List.copyOf(allowedCommands);
        this.clock = clock;
    }

    public MigrationPlan plan(AnalysisRun run) {
        Set<String> evidencePaths = run.evidence().stream()
                .map(ImpactEvidence::relativePath).collect(Collectors.toSet());
        Set<String> evidenceIds = run.evidence().stream()
                .map(ImpactEvidence::id).collect(Collectors.toSet());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changes", AgentPayloads.changes(run.changes()));
        payload.put("assessments", AgentPayloads.assessments(run.assessments()));
        payload.put("evidence", AgentPayloads.evidence(run.evidence()));
        payload.put("validationCommands", allowedCommands);

        PlanDraft draft = client.request(
                prompts.get("migration-planner", "v1"),
                codec.encode(payload),
                PlanDraft.class,
                d -> validate(d, evidencePaths, evidenceIds));

        if (draft.items().isEmpty()) {
            throw ContractGuardException.of(FailureCategory.UNSUPPORTED_FEATURE,
                    "no actionable breaking changes with evidence; there is nothing to plan",
                    "No remediation is required for this contract change set.");
        }

        List<PlanItem> items = new ArrayList<>();
        int index = 1;
        for (PlanDraft.Item item : draft.items()) {
            items.add(new PlanItem("item-" + index++, item.objective(), item.expectedFiles(),
                    item.proposedAction(), item.testsToUpdate(), item.validationCommand(),
                    item.risk(), item.rollback(), item.evidenceIds()));
        }
        int version = run.plan().map(p -> p.version() + 1).orElse(1);
        return new MigrationPlan(Ids.newId(), version, PlanHasher.hash(items), items, clock.instant());
    }

    private List<String> validate(PlanDraft draft, Set<String> evidencePaths, Set<String> evidenceIds) {
        if (draft.items() == null) {
            return List.of("'items' array is missing");
        }
        List<String> violations = new ArrayList<>();
        for (PlanDraft.Item item : draft.items()) {
            violations.addAll(validateItem(item, evidencePaths, evidenceIds));
        }
        return violations;
    }

    private List<String> validateItem(PlanDraft.Item item, Set<String> evidencePaths, Set<String> evidenceIds) {
        if (item.expectedFiles() == null || item.expectedFiles().isEmpty()) {
            return List.of("plan item '%s' lists no files".formatted(item.objective()));
        }
        List<String> violations = new ArrayList<>();
        for (String file : concat(item.expectedFiles(), item.testsToUpdate())) {
            if (!evidencePaths.contains(file)) {
                violations.add("file '%s' is not backed by evidence and cannot be planned".formatted(file));
            } else if (workspacePolicy.isBlockedFile(file)) {
                violations.add("file '%s' is blocked by policy".formatted(file));
            }
        }
        if (!allowedCommands.contains(item.validationCommand())) {
            violations.add("validation command '%s' is not allow-listed".formatted(item.validationCommand()));
        }
        if (item.evidenceIds() == null || item.evidenceIds().isEmpty()) {
            violations.add("plan item '%s' cites no evidence".formatted(item.objective()));
        } else {
            for (String id : item.evidenceIds()) {
                if (!evidenceIds.contains(id)) {
                    violations.add("plan item cites unknown evidence '%s'".formatted(id));
                }
            }
        }
        return violations;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        if (b != null) {
            all.addAll(b);
        }
        return all;
    }
}
