package com.contractguard.application.service;

import com.contractguard.application.agent.ImplementationAgent;
import com.contractguard.application.policy.SecretRedactor;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.BuildValidationPort;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.ObservabilityPort;
import com.contractguard.application.port.PatchPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.DeterministicRemediation;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Ids;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.PatchArtifact;
import com.contractguard.domain.RunFailure;
import com.contractguard.domain.RunState;
import com.contractguard.domain.ValidationResult;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Drives an approved run from branch creation through patching, validation
 * and the single bounded repair to a terminal state (§9 nodes 7-10). Every
 * mutation is preceded by deterministic safety checks; failures record
 * whether the repository was touched.
 */
public class ExecutionService {

    private static final int MAX_FAILURE_OUTPUT_CHARS = 6_000;
    // Step/tag vocabulary shared between span names, event-log "step" tags and
    // observability tag maps below -- named once so the three always agree.
    private static final String STEP_BRANCH = "branch";
    private static final String STEP_PATCH = "patch";
    private static final String STEP_VALIDATION = "validation";
    private static final String STATUS_STARTED = "STARTED";
    private static final String TAG_RUN_ID = "runId";
    private static final String TAG_REPOSITORY_ID = "repositoryId";

    private final RunRepository runs;
    private final RunEventLog events;
    private final GitWorkspacePort git;
    private final PatchPort patches;
    private final BuildValidationPort builds;
    private final SourceReaderPort sourceReader;
    private final ImplementationAgent agent;
    private final ArtifactStore artifacts;
    private final String validationCommandKey;
    private final AuditTrailService audit;
    private final ObservabilityPort observability;
    private final Clock clock;

    public ExecutionService(RunRepository runs, RunEventLog events, GitWorkspacePort git,
            PatchPort patches, BuildValidationPort builds, SourceReaderPort sourceReader,
            ImplementationAgent agent, ArtifactStore artifacts, String validationCommandKey,
            AuditTrailService audit, ObservabilityPort observability, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.git = git;
        this.patches = patches;
        this.builds = builds;
        this.sourceReader = sourceReader;
        this.agent = agent;
        this.artifacts = artifacts;
        this.validationCommandKey = validationCommandKey;
        this.audit = audit;
        this.observability = observability;
        this.clock = clock;
    }

    /** Precondition check + state move; called synchronously by the API before async execution. */
    public AnalysisRun beginExecution(String runId) {
        AnalysisRun run = RunLookup.require(runs, runId);
        if (!run.isApproved()) {
            throw ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                    "run %s has no recorded approval; execution is impossible".formatted(runId),
                    "Approve the migration plan first.");
        }
        RunState from = run.state();
        run.transitionTo(RunState.PREPARING_BRANCH, clock.instant());
        runs.save(run);
        audit.recordTransition(run, from, RunState.PREPARING_BRANCH);
        return run;
    }

    public void execute(String runId) {
        AnalysisRun run = RunLookup.require(runs, runId);
        try (ObservabilityPort.SpanHandle span = startSpan(run, "execute")) {
            try {
                prepareBranch(run);
                patchAndValidate(run);
            } catch (ContractGuardException e) {
                // Typed failures carry their own mutation flag (set where the mutation happened).
                span.recordError(e.getMessage());
                fail(run, e.failure());
            } catch (RuntimeException e) {
                span.recordError(e.getMessage());
                boolean patchWasApplied = run.patches().stream()
                        .anyMatch(p -> p.checkStatus() == PatchArtifact.CheckStatus.APPLIED);
                fail(run, new RunFailure(FailureCategory.INTERNAL_ERROR,
                        "unexpected execution error: " + e.getMessage(), patchWasApplied, null,
                        "Inspect the application logs; the working branch may need manual cleanup."));
            }
        }
    }

    private void prepareBranch(AnalysisRun run) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, STEP_BRANCH)) {
            events.append(run.id(), STEP_BRANCH, STATUS_STARTED, "Checking repository safety", RunEventLog.KIND_TOOL);
            GitWorkspacePort.GitStatus status = git.status(run.repositoryId());
            if (!status.clean()) {
                throw ContractGuardException.of(FailureCategory.DIRTY_REPOSITORY,
                        "repository has uncommitted changes: %s".formatted(
                                String.join(", ", status.dirtyEntries())),
                        "Commit, stash or reset the repository, then execute again.");
            }
            String branchName = "contractguard/run-" + Ids.shortId(run.id());
            if (git.branchExists(run.repositoryId(), branchName)) {
                throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                        "target branch %s already exists".formatted(branchName),
                        "Delete or rename the conflicting branch manually, then execute again.");
            }
            git.createBranch(run.repositoryId(), branchName);
            run.recordBranches(status.currentBranch(), branchName, clock.instant());
            runs.save(run);
            events.append(run.id(), STEP_BRANCH, "COMPLETED",
                    "Working branch %s created from %s".formatted(branchName, status.currentBranch()),
                    RunEventLog.KIND_TOOL);
            audit.recordRepositoryMutation(run, "Branch %s created from %s"
                    .formatted(branchName, status.currentBranch()));
        }
    }

    private void patchAndValidate(AnalysisRun run) {
        RunState fromPreparing = run.state();
        run.transitionTo(RunState.PATCHING, clock.instant());
        runs.save(run);
        audit.recordTransition(run, fromPreparing, RunState.PATCHING);
        MigrationPlan plan = run.plan().orElseThrow();

        applyPatch(run, plan, ImplementationAgent.IMPLEMENTATION_PROMPT, null, 1);
        ValidationResult first = validate(run, 1);
        if (first.successful()) {
            succeed(run);
            return;
        }
        if (!run.repairAllowed()) {
            throw validationFailed(first);
        }
        RunState fromValidating = run.state();
        run.transitionTo(RunState.REPAIRING, clock.instant());
        runs.save(run);
        audit.recordTransition(run, fromValidating, RunState.REPAIRING);
        events.append(run.id(), "repair", STATUS_STARTED,
                "First validation failed; attempting the single bounded repair", RunEventLog.KIND_LLM);
        String failureOutput = boundedFailureOutput(run, first);
        applyPatch(run, plan, ImplementationAgent.REPAIR_PROMPT, failureOutput, 2);
        ValidationResult second = validate(run, 2);
        if (second.successful()) {
            succeed(run);
            return;
        }
        throw validationFailed(second);
    }

    private void applyPatch(AnalysisRun run, MigrationPlan plan, String promptName,
            String failureOutput, int attempt) {
        try (ObservabilityPort.SpanHandle span = observability.startSpan(STEP_PATCH,
                Map.of(TAG_RUN_ID, run.id(), TAG_REPOSITORY_ID, run.repositoryId(), "attempt", String.valueOf(attempt)))) {
            Set<String> approvedFiles = plan.approvedFiles();
            Map<String, String> currentFiles = readApprovedFiles(run, approvedFiles);
            Map<String, String> rewrites = proposeRewrites(run, plan, promptName, failureOutput,
                    attempt, approvedFiles, currentFiles);

            String unifiedDiff = patches.buildUnifiedDiff(run.repositoryId(), rewrites);
            // Stored before any verdict so a rejected patch remains inspectable.
            String patchArtifactId = artifacts.save(run.id(), "patch-attempt-%d.diff".formatted(attempt),
                    unifiedDiff);
            PatchPort.PatchCheck check = requireSafePatch(run, approvedFiles, unifiedDiff, patchArtifactId);
            PatchArtifact artifact = new PatchArtifact(Ids.newId(), run.id(), attempt, unifiedDiff,
                    check.changedPaths(), PatchArtifact.CheckStatus.VALID, null);
            run.recordPatch(artifact, clock.instant());
            runs.save(run);
            events.append(run.id(), STEP_PATCH, "CHECKED",
                    "Patch verified with git apply --check (+%d/-%d lines across %d file(s))"
                            .formatted(check.addedLines(), check.removedLines(), check.changedPaths().size()),
                    RunEventLog.KIND_TOOL);

            patches.apply(run.repositoryId(), unifiedDiff);
            run.markPatchApplied(artifact.id(), clock.instant());
            runs.save(run);
            events.append(run.id(), STEP_PATCH, "APPLIED",
                    "Patch applied to %s".formatted(run.workingBranch()), RunEventLog.KIND_TOOL);
            audit.recordRepositoryMutation(run, "Patch attempt %d applied to %s (%d file(s))"
                    .formatted(attempt, run.workingBranch(), check.changedPaths().size()));
        }
    }

    /**
     * Solves the mechanical part of the plan (endpoint/property rename, enum value removal)
     * deterministically for every gateway, not just mock mode (ADR-0015), then lets the model
     * handle whatever is left — which may be nothing at all, so an empty model response is passed
     * through to {@link ImplementationAgent#propose} as legitimate rather than a validation failure.
     */
    private Map<String, String> proposeRewrites(AnalysisRun run, MigrationPlan plan, String promptName,
            String failureOutput, int attempt, Set<String> approvedFiles, Map<String, String> currentFiles) {
        Map<String, String> deterministicRewrites =
                DeterministicRemediation.applyToChangedFiles(currentFiles, run.changes());
        if (!deterministicRewrites.isEmpty()) {
            events.append(run.id(), STEP_PATCH, "MECHANICAL",
                    "Applied deterministic fixes to %d file(s) before the model"
                            .formatted(deterministicRewrites.size()), RunEventLog.KIND_TOOL);
        }
        Map<String, String> agentBaseline = new LinkedHashMap<>(currentFiles);
        agentBaseline.putAll(deterministicRewrites);

        events.append(run.id(), STEP_PATCH, STATUS_STARTED,
                "Attempt %d: generating file changes for %d approved file(s)"
                        .formatted(attempt, approvedFiles.size()), RunEventLog.KIND_LLM);
        Map<String, String> rewrites = new LinkedHashMap<>(deterministicRewrites);
        rewrites.putAll(agent.propose(promptName, run.changes(), plan,
                agentBaseline, failureOutput, !deterministicRewrites.isEmpty()));
        return rewrites;
    }

    private Map<String, String> readApprovedFiles(AnalysisRun run, Set<String> approvedFiles) {
        Map<String, String> currentFiles = new LinkedHashMap<>();
        for (String path : approvedFiles) {
            SourceReaderPort.FileContent content = sourceReader.read(run.repositoryId(), path, null, null);
            if (content.truncated()) {
                throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                        "approved file %s exceeds the remediation size limit".formatted(path),
                        "Remediation is limited to bounded text files.");
            }
            currentFiles.put(path, content.content());
        }
        return currentFiles;
    }

    /** Rejects the patch unless it is secret-free, touches only approved files and passes git's check. */
    private PatchPort.PatchCheck requireSafePatch(AnalysisRun run, Set<String> approvedFiles,
            String unifiedDiff, String patchArtifactId) {
        if (!SecretRedactor.redact(unifiedDiff).equals(unifiedDiff)) {
            throw new ContractGuardException(new RunFailure(FailureCategory.PATCH_REJECTED,
                    "generated patch contains likely secrets and was rejected",
                    false, patchArtifactId,
                    "Inspect the stored patch artifact; secrets must never enter patches."));
        }
        PatchPort.PatchCheck check = patches.check(run.repositoryId(), unifiedDiff);
        for (String path : check.changedPaths()) {
            if (!approvedFiles.contains(path)) {
                throw ContractGuardException.of(FailureCategory.PATCH_REJECTED,
                        "patch touches unapproved file %s".formatted(path),
                        "Only files listed in the approved plan may change.");
            }
        }
        if (!check.valid()) {
            throw new ContractGuardException(new RunFailure(FailureCategory.PATCH_REJECTED,
                    "patch failed safety checks: %s".formatted(String.join("; ", check.rejections())),
                    false, patchArtifactId,
                    "Inspect the stored patch artifact; the repository was not modified."));
        }
        return check;
    }

    private ValidationResult validate(AnalysisRun run, int attempt) {
        try (ObservabilityPort.SpanHandle span = observability.startSpan(STEP_VALIDATION,
                Map.of(TAG_RUN_ID, run.id(), TAG_REPOSITORY_ID, run.repositoryId(), "attempt", String.valueOf(attempt)))) {
            RunState from = run.state();
            run.transitionTo(RunState.VALIDATING, clock.instant());
            runs.save(run);
            audit.recordTransition(run, from, RunState.VALIDATING);
            events.append(run.id(), STEP_VALIDATION, STATUS_STARTED,
                    "Attempt %d: running %s".formatted(attempt, validationCommandKey), RunEventLog.KIND_TOOL);
            BuildValidationPort.BuildResult result = builds.run(run.repositoryId(), validationCommandKey);
            String artifactId = artifacts.save(run.id(), "validation-attempt-%d.log".formatted(attempt),
                    SecretRedactor.redact(result.output()));
            if (result.timedOut()) {
                span.recordError("validation timed out");
                throw new ContractGuardException(new RunFailure(FailureCategory.BUILD_TIMEOUT,
                        "validation exceeded the configured timeout", true, artifactId,
                        "Increase contractguard.validation.timeout or inspect the build log artifact."));
            }
            ValidationResult validation = new ValidationResult(attempt, validationCommandKey,
                    result.exitCode(), clock.instant().minus(result.duration()), result.duration(),
                    summarise(result), artifactId, result.exitCode() == 0);
            run.recordValidation(validation, clock.instant());
            runs.save(run);
            events.append(run.id(), STEP_VALIDATION, validation.successful() ? "PASSED" : "FAILED",
                    "Attempt %d: %s (%d ms)".formatted(attempt, validation.summary(),
                            result.duration().toMillis()),
                    RunEventLog.KIND_TOOL);
            if (!validation.successful()) {
                span.recordError(validation.summary());
            }
            return validation;
        }
    }

    private ObservabilityPort.SpanHandle startSpan(AnalysisRun run, String name) {
        return observability.startSpan(name, Map.of(TAG_RUN_ID, run.id(), TAG_REPOSITORY_ID, run.repositoryId()));
    }

    private void succeed(AnalysisRun run) {
        RunState from = run.state();
        run.transitionTo(RunState.SUCCEEDED, clock.instant());
        runs.save(run);
        audit.recordTransition(run, from, RunState.SUCCEEDED);
        events.append(run.id(), "run", "SUCCEEDED",
                "Remediation validated on %s; original branch %s untouched"
                        .formatted(run.workingBranch(), run.originalBranch()),
                RunEventLog.KIND_SYSTEM);
    }

    private ContractGuardException validationFailed(ValidationResult result) {
        return new ContractGuardException(new RunFailure(FailureCategory.VALIDATION_FAILURE,
                "validation attempt %d failed: %s".formatted(result.attempt(), result.summary()),
                true, result.outputArtifactId(),
                "Inspect the validation log artifact; discard the working branch to roll back."));
    }

    private String boundedFailureOutput(AnalysisRun run, ValidationResult failed) {
        String output = failed.outputArtifactId() == null ? failed.summary()
                : artifacts.read(run.id(), failed.outputArtifactId()).orElse(failed.summary());
        String redacted = SecretRedactor.redact(output);
        return redacted.length() <= MAX_FAILURE_OUTPUT_CHARS ? redacted
                : redacted.substring(redacted.length() - MAX_FAILURE_OUTPUT_CHARS);
    }

    /** Pulls the informative lines out of Maven output for the timeline and reports. */
    static String summarise(BuildValidationPort.BuildResult result) {
        String status = result.exitCode() == 0 ? "BUILD SUCCESS" : "BUILD FAILURE";
        String tests = result.output().lines()
                .filter(line -> line.contains("Tests run:") && line.contains("Failures:"))
                .reduce((first, second) -> second)
                .map(line -> line.replaceFirst("^\\[INFO\\]\\s*", "").strip())
                .orElse("no test summary found");
        String compilation = result.output().lines()
                .filter(line -> line.contains("COMPILATION ERROR"))
                .findFirst()
                .map(line -> "; compilation failed")
                .orElse("");
        return status + " — " + tests + compilation + (result.truncated() ? " (output truncated)" : "");
    }

    private void fail(AnalysisRun run, RunFailure failure) {
        RunState from = run.state();
        if (!from.isTerminal()) {
            run.markFailed(failure, clock.instant());
            runs.save(run);
            audit.recordTransition(run, from, RunState.FAILED);
        }
        events.append(run.id(), "run", "FAILED",
                "%s: %s%s".formatted(failure.category(), failure.message(),
                        failure.mutationOccurred()
                                ? " (working branch modified; original branch untouched)" : ""),
                RunEventLog.KIND_SYSTEM);
    }
}
