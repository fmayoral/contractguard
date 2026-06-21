package com.contractguard.application.service;

import com.contractguard.application.agent.ImplementationAgent;
import com.contractguard.application.policy.SecretRedactor;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.BuildValidationPort;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.PatchPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
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

    private final RunRepository runs;
    private final RunEventLog events;
    private final GitWorkspacePort git;
    private final PatchPort patches;
    private final BuildValidationPort builds;
    private final SourceReaderPort sourceReader;
    private final ImplementationAgent agent;
    private final ArtifactStore artifacts;
    private final String validationCommandKey;
    private final Clock clock;

    public ExecutionService(RunRepository runs, RunEventLog events, GitWorkspacePort git,
            PatchPort patches, BuildValidationPort builds, SourceReaderPort sourceReader,
            ImplementationAgent agent, ArtifactStore artifacts, String validationCommandKey, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.git = git;
        this.patches = patches;
        this.builds = builds;
        this.sourceReader = sourceReader;
        this.agent = agent;
        this.artifacts = artifacts;
        this.validationCommandKey = validationCommandKey;
        this.clock = clock;
    }

    /** Precondition check + state move; called synchronously by the API before async execution. */
    public AnalysisRun beginExecution(String runId) {
        AnalysisRun run = runs.findById(runId).orElseThrow(() -> ApprovalService.notFound(runId));
        if (!run.isApproved()) {
            throw ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                    "run %s has no recorded approval; execution is impossible".formatted(runId),
                    "Approve the migration plan first.");
        }
        run.transitionTo(RunState.PREPARING_BRANCH, clock.instant());
        runs.save(run);
        return run;
    }

    public void execute(String runId) {
        AnalysisRun run = runs.findById(runId).orElseThrow(() -> ApprovalService.notFound(runId));
        try {
            prepareBranch(run);
            patchAndValidate(run);
        } catch (ContractGuardException e) {
            // Typed failures carry their own mutation flag (set where the mutation happened).
            fail(run, e.failure());
        } catch (RuntimeException e) {
            boolean patchWasApplied = run.patches().stream()
                    .anyMatch(p -> p.checkStatus() == PatchArtifact.CheckStatus.APPLIED);
            fail(run, new RunFailure(FailureCategory.INTERNAL_ERROR,
                    "unexpected execution error: " + e.getMessage(), patchWasApplied, null,
                    "Inspect the application logs; the working branch may need manual cleanup."));
        }
    }

    private void prepareBranch(AnalysisRun run) {
        events.append(run.id(), "branch", "STARTED", "Checking repository safety", "{\"kind\":\"tool\"}");
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
        events.append(run.id(), "branch", "COMPLETED",
                "Working branch %s created from %s".formatted(branchName, status.currentBranch()),
                "{\"kind\":\"tool\"}");
    }

    private void patchAndValidate(AnalysisRun run) {
        run.transitionTo(RunState.PATCHING, clock.instant());
        runs.save(run);
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
        run.transitionTo(RunState.REPAIRING, clock.instant());
        runs.save(run);
        events.append(run.id(), "repair", "STARTED",
                "First validation failed; attempting the single bounded repair", "{\"kind\":\"llm\"}");
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
        Set<String> approvedFiles = plan.approvedFiles();
        Map<String, String> currentFiles = new LinkedHashMap<>();
        for (String path : approvedFiles) {
            SourceReaderPort.FileContent content = sourceReader.read(run.repositoryId(), path, null, null);
            if (content.truncated()) {
                throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                        "approved file %s exceeds the remediation size limit".formatted(path),
                        "The MVP remediates bounded text files only.");
            }
            currentFiles.put(path, content.content());
        }
        events.append(run.id(), "patch", "STARTED",
                "Attempt %d: generating file changes for %d approved file(s)"
                        .formatted(attempt, approvedFiles.size()), "{\"kind\":\"llm\"}");
        Map<String, String> rewrites = agent.propose(promptName, run.changes(), plan,
                currentFiles, failureOutput);

        String unifiedDiff = patches.buildUnifiedDiff(run.repositoryId(), rewrites);
        if (SecretRedactor.redact(unifiedDiff).length() != unifiedDiff.length()) {
            throw ContractGuardException.of(FailureCategory.PATCH_REJECTED,
                    "generated patch contains likely secrets and was rejected",
                    "Inspect the agent output; secrets must never enter patches.");
        }
        PatchPort.PatchCheck check = patches.check(run.repositoryId(), unifiedDiff);
        String patchArtifactId = artifacts.save(run.id(), "patch-attempt-%d.diff".formatted(attempt),
                unifiedDiff);
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
        PatchArtifact artifact = new PatchArtifact(Ids.newId(), run.id(), attempt, unifiedDiff,
                check.changedPaths(), PatchArtifact.CheckStatus.VALID, null);
        run.recordPatch(artifact, clock.instant());
        runs.save(run);
        events.append(run.id(), "patch", "CHECKED",
                "Patch verified with git apply --check (+%d/-%d lines across %d file(s))"
                        .formatted(check.addedLines(), check.removedLines(), check.changedPaths().size()),
                "{\"kind\":\"tool\"}");

        patches.apply(run.repositoryId(), unifiedDiff);
        run.markPatchApplied(artifact.id(), clock.instant());
        runs.save(run);
        events.append(run.id(), "patch", "APPLIED",
                "Patch applied to %s".formatted(run.workingBranch()), "{\"kind\":\"tool\"}");
    }

    private ValidationResult validate(AnalysisRun run, int attempt) {
        run.transitionTo(RunState.VALIDATING, clock.instant());
        runs.save(run);
        events.append(run.id(), "validation", "STARTED",
                "Attempt %d: running %s".formatted(attempt, validationCommandKey), "{\"kind\":\"tool\"}");
        BuildValidationPort.BuildResult result = builds.run(run.repositoryId(), validationCommandKey);
        String artifactId = artifacts.save(run.id(), "validation-attempt-%d.log".formatted(attempt),
                SecretRedactor.redact(result.output()));
        if (result.timedOut()) {
            throw new ContractGuardException(new RunFailure(FailureCategory.BUILD_TIMEOUT,
                    "validation exceeded the configured timeout", true, artifactId,
                    "Increase contractguard.validation.timeout or inspect the build log artifact."));
        }
        ValidationResult validation = new ValidationResult(attempt, validationCommandKey,
                result.exitCode(), clock.instant().minus(result.duration()), result.duration(),
                summarise(result), artifactId, result.exitCode() == 0);
        run.recordValidation(validation, clock.instant());
        runs.save(run);
        events.append(run.id(), "validation", validation.successful() ? "PASSED" : "FAILED",
                "Attempt %d: %s (%d ms)".formatted(attempt, validation.summary(),
                        result.duration().toMillis()),
                "{\"kind\":\"tool\"}");
        return validation;
    }

    private void succeed(AnalysisRun run) {
        run.transitionTo(RunState.SUCCEEDED, clock.instant());
        runs.save(run);
        events.append(run.id(), "run", "SUCCEEDED",
                "Remediation validated on %s; original branch %s untouched"
                        .formatted(run.workingBranch(), run.originalBranch()),
                "{\"kind\":\"system\"}");
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
        if (!run.state().isTerminal()) {
            run.markFailed(failure, clock.instant());
            runs.save(run);
        }
        events.append(run.id(), "run", "FAILED",
                "%s: %s%s".formatted(failure.category(), failure.message(),
                        failure.mutationOccurred()
                                ? " (working branch modified; original branch untouched)" : ""),
                "{\"kind\":\"system\"}");
    }
}
