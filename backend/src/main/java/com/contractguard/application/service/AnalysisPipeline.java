package com.contractguard.application.service;

import com.contractguard.application.agent.ChangeExplainer;
import com.contractguard.application.agent.ImpactInvestigator;
import com.contractguard.application.agent.MigrationPlanner;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.ObservabilityPort;
import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.ImpactAssessment;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.RunFailure;
import com.contractguard.domain.RunState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Drives one run from CREATED to AWAITING_APPROVAL (§9 nodes 1-5). Every step
 * transitions the state machine, persists the aggregate and emits timeline
 * events; any typed failure finalises the run as FAILED with its category.
 */
public class AnalysisPipeline {

    // Step names shared between span names, event-log "step" tags and the transition/complete
    // helpers below -- named once so the three always agree.
    private static final String STEP_INPUT_VALIDATION = "input-validation";
    private static final String STEP_CHANGE_EXPLAINER = "change-explainer";
    private static final String STEP_SEARCH = "search";
    private static final String STEP_ASSESSMENT = "assessment";
    private static final String STEP_PLANNING = "planning";

    private final RunRepository runs;
    private final RunEventLog events;
    private final WorkspacePolicy workspacePolicy;
    private final OpenApiDiffPort diffPort;
    private final EvidenceCollector evidenceCollector;
    private final ChangeExplainer changeExplainer;
    private final ImpactInvestigator investigator;
    private final MigrationPlanner planner;
    private final JsonCodec codec;
    private final AuditTrailService audit;
    private final ObservabilityPort observability;
    private final Clock clock;

    public AnalysisPipeline(RunRepository runs, RunEventLog events, WorkspacePolicy workspacePolicy,
            OpenApiDiffPort diffPort, EvidenceCollector evidenceCollector, ChangeExplainer changeExplainer,
            ImpactInvestigator investigator, MigrationPlanner planner, JsonCodec codec,
            AuditTrailService audit, ObservabilityPort observability, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.workspacePolicy = workspacePolicy;
        this.diffPort = diffPort;
        this.evidenceCollector = evidenceCollector;
        this.changeExplainer = changeExplainer;
        this.investigator = investigator;
        this.planner = planner;
        this.codec = codec;
        this.audit = audit;
        this.observability = observability;
        this.clock = clock;
    }

    public void analyse(String runId, Path oldSpec, Path newSpec) {
        AnalysisRun run = runs.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("unknown run " + runId));
        try (ObservabilityPort.SpanHandle span = startSpan(run, "analyse")) {
            try {
                validateInputs(run, oldSpec, newSpec);
                diffAndExplain(run, oldSpec, newSpec);
                collectEvidence(run);
                assess(run);
                plan(run);
            } catch (ContractGuardException e) {
                span.recordError(e.getMessage());
                fail(run, e.failure());
            } catch (RuntimeException e) {
                span.recordError(e.getMessage());
                fail(run, new RunFailure(FailureCategory.INTERNAL_ERROR,
                        "unexpected analysis error: " + e.getMessage(), false, null,
                        "Inspect the application logs for the stack trace."));
            }
        }
    }

    private void validateInputs(AnalysisRun run, Path oldSpec, Path newSpec) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, STEP_INPUT_VALIDATION)) {
            transition(run, RunState.VALIDATING_INPUT, STEP_INPUT_VALIDATION, "Validating inputs");
            if (!Files.isRegularFile(oldSpec) || !Files.isRegularFile(newSpec)) {
                throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                        "one or both specification files do not exist",
                        "Select specifications from the configured directory.");
            }
            Path repoRoot = workspacePolicy.resolveRepository(run.repositoryId());
            if (!Files.isDirectory(repoRoot.resolve(".git"))) {
                throw ContractGuardException.of(FailureCategory.REPOSITORY_OUTSIDE_WORKSPACE,
                        "repository '%s' is not a Git repository".formatted(run.repositoryId()),
                        "Run the demo reset script to materialise the repository.");
            }
            if (!Files.exists(repoRoot.resolve("mvnw")) && !Files.exists(repoRoot.resolve("mvnw.cmd"))) {
                throw ContractGuardException.of(FailureCategory.UNSUPPORTED_FEATURE,
                        "repository '%s' has no Maven wrapper".formatted(run.repositoryId()),
                        "Only Maven-wrapper builds are supported.");
            }
            List<AnalysisRun> active = runs.findActiveByRepository(run.repositoryId());
            boolean busy = active.stream().anyMatch(other -> !other.id().equals(run.id()));
            if (busy) {
                throw ContractGuardException.of(FailureCategory.REPOSITORY_BUSY,
                        "repository '%s' is used by another active run".formatted(run.repositoryId()),
                        "Wait for the other run to finish or cancel it.");
            }
            complete(run, STEP_INPUT_VALIDATION, "Inputs validated", null);
        }
    }

    private void diffAndExplain(AnalysisRun run, Path oldSpec, Path newSpec) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, "diff")) {
            transition(run, RunState.DIFFING, "diff", "Comparing specifications");
            OpenApiDiffPort.DiffResult result = diffPort.diff(oldSpec, newSpec);
            run.recordSpecs(fileName(oldSpec), fileName(newSpec),
                    result.oldSpecHash(), result.newSpecHash(), clock.instant());
            run.recordChanges(result.changes(), clock.instant());
            runs.save(run);
            complete(run, "diff", "%d change(s) detected".formatted(result.changes().size()),
                    Map.of("changes", result.changes().size(), "warnings", result.warnings()));
        }

        try (ObservabilityPort.SpanHandle span = startSpan(run, STEP_CHANGE_EXPLAINER)) {
            events.append(run.id(), STEP_CHANGE_EXPLAINER, "STARTED",
                    "Explaining changes", RunEventLog.KIND_LLM);
            Map<String, String> explanations = changeExplainer.explain(run.changes());
            explanations.forEach((changeId, text) -> run.attachExplanation(changeId, text, clock.instant()));
            runs.save(run);
            events.append(run.id(), STEP_CHANGE_EXPLAINER, "COMPLETED",
                    "%d change(s) explained".formatted(explanations.size()), RunEventLog.KIND_LLM);
        }
    }

    private void collectEvidence(AnalysisRun run) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, STEP_SEARCH)) {
            transition(run, RunState.SEARCHING, STEP_SEARCH, "Searching consumer repository");
            List<ImpactEvidence> evidence = evidenceCollector.collect(run.repositoryId(), run.changes());
            run.recordEvidence(evidence, clock.instant());
            runs.save(run);
            complete(run, STEP_SEARCH, "%d evidence match(es) collected".formatted(evidence.size()),
                    Map.of("evidence", evidence.size()));
        }
    }

    private void assess(AnalysisRun run) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, STEP_ASSESSMENT)) {
            transition(run, RunState.ASSESSING, STEP_ASSESSMENT, "Assessing impact");
            List<ImpactAssessment> assessments = investigator.investigate(
                    run.id(), run.repositoryId(), run.changes(), run.evidence(), events);
            run.recordAssessments(assessments, clock.instant());
            runs.save(run);
            complete(run, STEP_ASSESSMENT, "%d impact assessment(s) produced".formatted(assessments.size()),
                    Map.of("assessments", assessments.size(), "kind", "llm"));
        }
    }

    private void plan(AnalysisRun run) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, STEP_PLANNING)) {
            transition(run, RunState.PLANNING, STEP_PLANNING, "Generating migration plan");
            MigrationPlan plan = planner.plan(run);
            run.attachPlan(plan, clock.instant());
            run.transitionTo(RunState.AWAITING_APPROVAL, clock.instant());
            runs.save(run);
            audit.recordTransition(run, RunState.PLANNING, RunState.AWAITING_APPROVAL);
            complete(run, STEP_PLANNING, "Plan v%d with %d item(s) ready".formatted(
                    plan.version(), plan.items().size()),
                    Map.of("planHash", plan.hash(), "items", plan.items().size(), "kind", "llm"));
            events.append(run.id(), "approval", "WAITING",
                    "Awaiting human approval; no modification will happen before an approval is recorded",
                    RunEventLog.KIND_SYSTEM);
        }
    }

    private ObservabilityPort.SpanHandle startSpan(AnalysisRun run, String name) {
        return observability.startSpan(name, Map.of("runId", run.id(), "repositoryId", run.repositoryId()));
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private void transition(AnalysisRun run, RunState state, String step, String message) {
        RunState from = run.state();
        run.transitionTo(state, clock.instant());
        runs.save(run);
        events.append(run.id(), step, "STARTED", message, RunEventLog.KIND_TOOL);
        audit.recordTransition(run, from, state);
    }

    private void complete(AnalysisRun run, String step, String message, Map<String, Object> metadata) {
        events.append(run.id(), step, "COMPLETED", message, metadataJson(metadata));
    }

    private String metadataJson(Map<String, Object> metadata) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        merged.put("kind", "tool");
        if (metadata != null) {
            merged.putAll(metadata);
        }
        return codec.encode(merged);
    }

    private void fail(AnalysisRun run, RunFailure failure) {
        RunState from = run.state();
        if (!from.isTerminal()) {
            run.markFailed(failure, clock.instant());
            runs.save(run);
            audit.recordTransition(run, from, RunState.FAILED);
        }
        events.append(run.id(), "run", "FAILED",
                "%s: %s".formatted(failure.category(), failure.message()), RunEventLog.KIND_SYSTEM);
    }
}
