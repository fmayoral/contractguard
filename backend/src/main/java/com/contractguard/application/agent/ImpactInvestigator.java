package com.contractguard.application.agent;

import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.llm.contract.AssessmentDraft;
import com.contractguard.application.llm.contract.InvestigatorAction;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.Confidence;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.ImpactAssessment;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The bounded agentic tool user (§9 node 4). The model may call the two
 * registered tools within a step budget, then must finish with assessments
 * that cite existing evidence. Unregistered tools, malformed arguments and
 * uncited claims are rejected (with one retry) — the loop never trusts prose.
 */
public class ImpactInvestigator {

    private static final int MAX_SEARCH_RESULTS = 50;
    private static final int MAX_TOOL_CONTENT_CHARS = 4_000;

    private final LlmJsonClient client;
    private final PromptLibrary prompts;
    private final JsonCodec codec;
    private final RepositorySearchPort searchPort;
    private final SourceReaderPort sourceReader;
    private final int maxSteps;

    public ImpactInvestigator(LlmJsonClient client, PromptLibrary prompts, JsonCodec codec,
            RepositorySearchPort searchPort, SourceReaderPort sourceReader, int maxSteps) {
        this.client = client;
        this.prompts = prompts;
        this.codec = codec;
        this.searchPort = searchPort;
        this.sourceReader = sourceReader;
        this.maxSteps = maxSteps;
    }

    public List<ImpactAssessment> investigate(String runId, String repositoryId,
            List<ApiChange> changes, List<ImpactEvidence> evidence, RunEventLog events) {
        Set<String> changeIds = changes.stream().map(ApiChange::id).collect(Collectors.toSet());
        Set<String> evidenceIds = evidence.stream().map(ImpactEvidence::id).collect(Collectors.toSet());
        List<Map<String, Object>> toolResults = new ArrayList<>();

        for (int step = 0; step < maxSteps; step++) {
            int stepsRemaining = maxSteps - step;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("changes", AgentPayloads.changes(changes));
            payload.put("evidence", AgentPayloads.evidence(evidence));
            payload.put("toolResults", toolResults);
            payload.put("stepsRemaining", stepsRemaining);

            InvestigatorAction action = client.request(
                    prompts.get("impact-assessor", "v1"),
                    codec.encode(payload),
                    InvestigatorAction.class,
                    a -> validateAction(a, changeIds, evidenceIds));

            switch (action.action()) {
                case InvestigatorAction.ACTION_SEARCH -> toolResults.add(executeSearch(
                        runId, repositoryId, action.search(), events));
                case InvestigatorAction.ACTION_READ -> toolResults.add(executeRead(
                        runId, repositoryId, action.read(), events));
                case InvestigatorAction.ACTION_FINISH -> {
                    return toAssessments(action.assessments());
                }
                default -> throw new IllegalStateException("validator let through " + action.action());
            }
        }
        throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                "investigator exhausted its %d-step budget without finishing".formatted(maxSteps),
                "Increase contractguard.llm.max-workflow-steps or inspect the agent trace.");
    }

    private Map<String, Object> executeSearch(String runId, String repositoryId,
            InvestigatorAction.SearchArgs args, RunEventLog events) {
        int maxResults = Math.min(args.maxResults() == null ? 30 : args.maxResults(), MAX_SEARCH_RESULTS);
        events.append(runId, "assessment", "TOOL",
                "search_repository: \"%s\"".formatted(args.query()), RunEventLog.KIND_TOOL);
        List<RepositorySearchPort.SearchMatch> matches =
                searchPort.search(repositoryId, args.query(), args.glob(), maxResults);
        String rendered = matches.stream()
                .map(m -> "%s:%d: %s".formatted(m.relativePath(), m.lineNumber(), m.lineText()))
                .collect(Collectors.joining("\n"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", InvestigatorAction.ACTION_SEARCH);
        result.put("detail", args.query());
        result.put("content", bounded(rendered));
        return result;
    }

    private Map<String, Object> executeRead(String runId, String repositoryId,
            InvestigatorAction.ReadArgs args, RunEventLog events) {
        events.append(runId, "assessment", "TOOL",
                "read_source_file: %s".formatted(args.path()), RunEventLog.KIND_TOOL);
        SourceReaderPort.FileContent content =
                sourceReader.read(repositoryId, args.path(), args.startLine(), args.endLine());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", InvestigatorAction.ACTION_READ);
        result.put("detail", "%s:%d-%d".formatted(content.relativePath(), content.startLine(), content.endLine()));
        result.put("content", bounded(content.content()));
        return result;
    }

    private static String bounded(String content) {
        return content.length() <= MAX_TOOL_CONTENT_CHARS
                ? content : content.substring(0, MAX_TOOL_CONTENT_CHARS) + "\n[truncated]";
    }

    private List<String> validateAction(InvestigatorAction action,
            Set<String> changeIds, Set<String> evidenceIds) {
        if (action.action() == null) {
            return List.of("'action' is missing");
        }
        return switch (action.action()) {
            case InvestigatorAction.ACTION_SEARCH -> action.search() == null || isBlank(action.search().query())
                    ? List.of("search_repository requires a non-empty 'search.query'") : List.of();
            case InvestigatorAction.ACTION_READ -> action.read() == null || isBlank(action.read().path())
                    ? List.of("read_source_file requires a non-empty 'read.path'") : List.of();
            case InvestigatorAction.ACTION_FINISH -> validateAssessments(action.assessments(), changeIds, evidenceIds);
            default -> List.of("'%s' is not a registered tool; allowed: search_repository, read_source_file, finish"
                    .formatted(action.action()));
        };
    }

    private List<String> validateAssessments(List<AssessmentDraft> drafts,
            Set<String> changeIds, Set<String> evidenceIds) {
        if (drafts == null) {
            return List.of("finish requires an 'assessments' array");
        }
        List<String> violations = new ArrayList<>();
        for (AssessmentDraft draft : drafts) {
            violations.addAll(validateAssessmentDraft(draft, changeIds, evidenceIds));
        }
        return violations;
    }

    private List<String> validateAssessmentDraft(AssessmentDraft draft, Set<String> changeIds,
            Set<String> evidenceIds) {
        List<String> violations = new ArrayList<>();
        if (draft.apiChangeId() == null || !changeIds.contains(draft.apiChangeId())) {
            violations.add("assessment references unknown change '%s'".formatted(draft.apiChangeId()));
        }
        if (draft.evidenceIds() == null || draft.evidenceIds().isEmpty()) {
            violations.add("assessment for '%s' cites no evidence".formatted(draft.apiChangeId()));
        } else {
            for (String id : draft.evidenceIds()) {
                if (!evidenceIds.contains(id)) {
                    violations.add("assessment cites unknown evidence '%s'".formatted(id));
                }
            }
        }
        if (parseEnum(Severity.class, draft.severity()) == null) {
            violations.add("invalid severity '%s'".formatted(draft.severity()));
        }
        if (parseEnum(Confidence.class, draft.confidence()) == null) {
            violations.add("invalid confidence '%s'".formatted(draft.confidence()));
        }
        return violations;
    }

    private List<ImpactAssessment> toAssessments(List<AssessmentDraft> drafts) {
        List<ImpactAssessment> assessments = new ArrayList<>();
        int index = 1;
        for (AssessmentDraft draft : drafts) {
            assessments.add(new ImpactAssessment(
                    "as-" + index++,
                    draft.apiChangeId(),
                    draft.component(),
                    parseEnum(Severity.class, draft.severity()),
                    parseEnum(Confidence.class, draft.confidence()),
                    draft.failureMode(),
                    draft.recommendedAction(),
                    draft.assumptions() == null ? List.of() : draft.assumptions(),
                    draft.evidenceIds()));
        }
        return assessments;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
