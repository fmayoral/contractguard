package com.contractguard.application.service;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Ids;
import com.contractguard.domain.RunFailure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Creates runs (FR-001) and launches the asynchronous analysis. Specification
 * files are restricted to the configured specs directory; repositories to the
 * workspace roots.
 */
public class RunService {

    private final RunRepository runs;
    private final RunEventLog events;
    private final WorkspacePolicy workspacePolicy;
    private final RemoteRepositoryService remoteRepositories;
    private final AnalysisPipeline pipeline;
    private final Path specsDirectory;
    private final Executor executor;
    private final Clock clock;

    public RunService(RunRepository runs, RunEventLog events, WorkspacePolicy workspacePolicy,
            RemoteRepositoryService remoteRepositories, AnalysisPipeline pipeline, Path specsDirectory,
            Executor executor, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.workspacePolicy = workspacePolicy;
        this.remoteRepositories = remoteRepositories;
        this.pipeline = pipeline;
        this.specsDirectory = specsDirectory.toAbsolutePath().normalize();
        this.executor = executor;
        this.clock = clock;
    }

    public AnalysisRun createRun(String name, String repositoryId, String oldSpecFile, String newSpecFile) {
        Path oldSpec = resolveSpec(oldSpecFile);
        Path newSpec = resolveSpec(newSpecFile);
        boolean busy = !runs.findActiveByRepository(repositoryId).isEmpty();
        if (busy) {
            throw ContractGuardException.of(FailureCategory.REPOSITORY_BUSY,
                    "repository '%s' is used by another active run".formatted(repositoryId),
                    "Wait for the active run to finish or cancel it.");
        }
        // Remote repos clone/refresh into a cache dir that is itself a workspace root, so the
        // resolve below (and everything downstream) sees an ordinary local repository (ADR-0007).
        remoteRepositories.ensureLocalClone(repositoryId);
        workspacePolicy.resolveRepository(repositoryId);
        String runId = Ids.newId();
        String runName = name == null || name.isBlank()
                ? "run-" + Ids.shortId(runId) : name.strip();
        AnalysisRun run = new AnalysisRun(runId, runName, repositoryId, Ids.newId(), clock.instant());
        runs.save(run);
        events.append(runId, "run", "CREATED",
                "Run '%s' created for repository '%s'".formatted(runName, repositoryId),
                "{\"kind\":\"system\"}");
        executor.execute(() -> pipeline.analyse(runId, oldSpec, newSpec));
        return run;
    }

    /** Lists the specification files selectable for a run. */
    public List<String> listSpecificationFiles() {
        if (!Files.isDirectory(specsDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(specsDirectory)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new ContractGuardException(new RunFailure(FailureCategory.INTERNAL_ERROR,
                    "cannot list specification directory", false, null,
                    "Check the configured contractguard.specs.directory."), e);
        }
    }

    public List<String> listRepositories() {
        return workspacePolicy.listRepositories();
    }

    /** On startup, runs interrupted by a restart are finalised as FAILED (plan §7 A6). */
    public int failInterruptedRuns() {
        int count = 0;
        for (AnalysisRun run : runs.findAll()) {
            if (!run.state().isTerminal()) {
                run.markFailed(new RunFailure(FailureCategory.INTERNAL_ERROR,
                        "run was interrupted by an application restart while in state " + run.state(),
                        false, null,
                        "Start a new run; the repository was not left mid-mutation by the analysis phase."),
                        clock.instant());
                runs.save(run);
                events.append(run.id(), "run", "FAILED",
                        "Run interrupted by application restart", "{\"kind\":\"system\"}");
                count++;
            }
        }
        return count;
    }

    private Path resolveSpec(String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "invalid specification file name: " + fileName,
                    "Choose a file from the specification directory listing.");
        }
        Path resolved = specsDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(specsDirectory) || !Files.isRegularFile(resolved)) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "specification file not found: " + fileName,
                    "Choose a file from the specification directory listing.");
        }
        return resolved;
    }
}
