package com.contractguard.application.service;

import com.contractguard.application.policy.RepositoryLock;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Ids;
import com.contractguard.domain.RemoteRepository;
import com.contractguard.domain.RunFailure;
import com.contractguard.domain.RunState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Creates runs (FR-001) and launches the asynchronous analysis. Repositories are restricted to
 * the workspace roots; qualified spec IDs are resolved via {@link SpecResolutionService}
 * (extracted so {@link SpecPreviewService} can resolve them too, without depending on run
 * creation).
 */
public class RunService {

    private final RunRepository runs;
    private final RunEventLog events;
    private final WorkspacePolicy workspacePolicy;
    private final RemoteRepositoryService remoteRepositories;
    private final SpecSourceService specSources;
    private final SpecResolutionService specResolution;
    private final RepositoryLock repositoryLock;
    private final AnalysisPipeline pipeline;
    private final Path specsDirectory;
    private final Path uploadedSpecsDirectory;
    private final Executor executor;
    private final int maxActiveRuns;
    private final Clock clock;

    public RunService(RunRepository runs, RunEventLog events, WorkspacePolicy workspacePolicy,
            RemoteRepositoryService remoteRepositories, SpecSourceService specSources,
            SpecResolutionService specResolution, RepositoryLock repositoryLock, AnalysisPipeline pipeline,
            Path specsDirectory, Path uploadedSpecsDirectory, Executor executor, int maxActiveRuns, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.workspacePolicy = workspacePolicy;
        this.remoteRepositories = remoteRepositories;
        this.specSources = specSources;
        this.specResolution = specResolution;
        this.repositoryLock = repositoryLock;
        this.pipeline = pipeline;
        this.specsDirectory = specsDirectory.toAbsolutePath().normalize();
        this.uploadedSpecsDirectory = uploadedSpecsDirectory.toAbsolutePath().normalize();
        this.executor = executor;
        this.maxActiveRuns = maxActiveRuns;
        this.clock = clock;
    }

    public AnalysisRun createRun(String name, String repositoryId, String oldSpecFile, String newSpecFile) {
        Path oldSpec = specResolution.resolve(oldSpecFile);
        Path newSpec = specResolution.resolve(newSpecFile);
        if (!repositoryLock.tryAcquire(repositoryId)) {
            throw ContractGuardException.of(FailureCategory.REPOSITORY_BUSY,
                    "repository '%s' is used by another active run".formatted(repositoryId),
                    "Wait for the active run to finish or cancel it.");
        }
        try {
            AnalysisRun run = createRunLocked(name, repositoryId, oldSpecFile, newSpecFile);
            executor.execute(() -> pipeline.analyse(run.id(), oldSpec, newSpec));
            return run;
        } finally {
            // Only the tiny check-then-insert window needs the lock: once the row exists,
            // findActiveByRepository already represents "busy" for the run's whole lifetime (ADR-0009).
            repositoryLock.release(repositoryId);
        }
    }

    private AnalysisRun createRunLocked(String name, String repositoryId, String oldSpecFile,
            String newSpecFile) {
        boolean busy = !runs.findActiveByRepository(repositoryId).isEmpty();
        if (busy) {
            throw ContractGuardException.of(FailureCategory.REPOSITORY_BUSY,
                    "repository '%s' is used by another active run".formatted(repositoryId),
                    "Wait for the active run to finish or cancel it.");
        }
        if (maxActiveRuns > 0 && runs.countActive() >= maxActiveRuns) {
            throw ContractGuardException.of(FailureCategory.RUN_QUEUE_FULL,
                    "%d run(s) are already active; the queue is full".formatted(maxActiveRuns),
                    "Wait for an active run to finish, or raise contractguard.concurrency.max-active-runs.");
        }
        // Remote repos clone/refresh into a cache dir that is itself a workspace root, so the
        // resolve below (and everything downstream) sees an ordinary local repository (ADR-0007).
        remoteRepositories.ensureLocalClone(repositoryId);
        workspacePolicy.resolveRepository(repositoryId);
        String runId = Ids.newId();
        String runName = name == null || name.isBlank()
                ? "run-" + Ids.shortId(runId) : name.strip();
        AnalysisRun run = new AnalysisRun(runId, runName, repositoryId, Ids.newId(), clock.instant(),
                oldSpecFile, newSpecFile);
        runs.save(run);
        events.append(runId, "run", "CREATED",
                "Run '%s' created for repository '%s'".formatted(runName, repositoryId),
                "{\"kind\":\"system\"}");
        return run;
    }

    /**
     * Lists every specification file selectable for a run, regardless of origin (FR-043, ADR-0012):
     * the bundled local directory, anything uploaded, and every registered spec-source repository
     * (clone-or-refreshed here, so this list is always current).
     */
    public List<SpecOption> listSpecOptions() {
        List<SpecOption> options = new ArrayList<>();
        listFileNames(specsDirectory).forEach(name ->
                options.add(new SpecOption("local:" + name, name, SpecOption.SpecOrigin.LOCAL, null)));
        listFileNames(uploadedSpecsDirectory).forEach(name ->
                options.add(new SpecOption("upload:" + name, name, SpecOption.SpecOrigin.UPLOADED, null)));
        specSources.listSpecFiles().forEach(file -> options.add(new SpecOption(
                "source:" + file.sourceId() + ":" + file.fileName(), file.fileName(),
                SpecOption.SpecOrigin.SPEC_SOURCE, file.sourceId())));
        return options;
    }

    /** @param fileName the original upload name; a same-name re-upload overwrites (ADR-0012 decision #5) */
    public SpecOption uploadSpecification(String fileName, String content) {
        String safeName = sanitiseUploadFileName(fileName);
        try {
            Files.createDirectories(uploadedSpecsDirectory);
            Files.writeString(uploadedSpecsDirectory.resolve(safeName), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store uploaded specification " + safeName, e);
        }
        return new SpecOption("upload:" + safeName, safeName, SpecOption.SpecOrigin.UPLOADED, null);
    }

    /** Removes a previously uploaded specification (FR-044). A no-op if it was never uploaded. */
    public void deleteUploadedSpecification(String fileName) {
        String safeName = sanitiseUploadFileName(fileName);
        try {
            Files.deleteIfExists(uploadedSpecsDirectory.resolve(safeName));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete uploaded specification " + safeName, e);
        }
    }

    private static String sanitiseUploadFileName(String fileName) {
        String name = "";
        if (fileName != null) {
            try {
                Path lastSegment = Path.of(fileName).getFileName();
                name = lastSegment == null ? "" : lastSegment.toString();
            } catch (InvalidPathException e) {
                name = "";
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isBlank() || name.contains("..")
                || !(lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json"))) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "unsupported specification file name: " + fileName,
                    "Upload a .yaml, .yml or .json OpenAPI specification.");
        }
        return name;
    }

    private static List<String> listFileNames(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ContractGuardException(new RunFailure(FailureCategory.INTERNAL_ERROR,
                    "cannot list specification directory " + directory, false, null,
                    "Check the configured contractguard.specs.directory."), e);
        }
    }

    /** Local workspace repositories, excluding any that are also remote-registered (they belong in {@link #listRemoteRepositories}). */
    public List<String> listRepositories() {
        List<String> remoteIds = listRemoteRepositories();
        return workspacePolicy.listRepositories().stream()
                .filter(id -> !remoteIds.contains(id))
                .toList();
    }

    public List<String> listRemoteRepositories() {
        return remoteRepositories.listRegistered().stream().map(RemoteRepository::repositoryId).toList();
    }

    /**
     * On startup, runs interrupted by the previous shutdown are recovered (FR-032, ADR-0009):
     * {@code AWAITING_APPROVAL} runs are left untouched (nothing was in flight — they're just
     * waiting on a human, restart or not), runs still {@code CREATED} are safely re-dispatched
     * from scratch (no repository mutation could have started yet), and every other non-terminal
     * state is finalised as FAILED with a clean remediation message — deliberately not resumed
     * mid-mutation, since the state machine's transitions are one-shot by design.
     */
    public InterruptedRunRecovery failInterruptedRuns() {
        int resumed = 0;
        int failed = 0;
        for (AnalysisRun run : runs.findAll()) {
            RunState state = run.state();
            if (state.isTerminal() || state == RunState.AWAITING_APPROVAL) {
                continue;
            }
            if (state == RunState.CREATED) {
                if (resumeCreated(run)) {
                    resumed++;
                } else {
                    failed++;
                }
                continue;
            }
            run.markFailed(new RunFailure(FailureCategory.INTERNAL_ERROR,
                    "run was interrupted by an application restart while in state " + state,
                    false, null,
                    "Start a new run; the repository was not left mid-mutation by the analysis phase."),
                    clock.instant());
            runs.save(run);
            events.append(run.id(), "run", "FAILED",
                    "Run interrupted by application restart", "{\"kind\":\"system\"}");
            failed++;
        }
        return new InterruptedRunRecovery(resumed, failed);
    }

    /** @return true if the run was successfully re-dispatched, false if it had to be failed instead */
    private boolean resumeCreated(AnalysisRun run) {
        try {
            Path oldSpec = specResolution.resolve(run.oldSpecFile());
            Path newSpec = specResolution.resolve(run.newSpecFile());
            events.append(run.id(), "run", "RESUMED",
                    "Resuming analysis interrupted by the previous shutdown while still CREATED",
                    "{\"kind\":\"system\"}");
            executor.execute(() -> pipeline.analyse(run.id(), oldSpec, newSpec));
            return true;
        } catch (ContractGuardException e) {
            run.markFailed(e.failure(), clock.instant());
            runs.save(run);
            events.append(run.id(), "run", "FAILED",
                    "Cannot resume: " + e.failure().message(), "{\"kind\":\"system\"}");
            return false;
        }
    }

    public record InterruptedRunRecovery(int resumed, int failed) {
    }
}
