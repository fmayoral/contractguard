package com.contractguard.application.service;

import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.ObservabilityPort;
import com.contractguard.application.port.PullRequestPort;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Ids;
import com.contractguard.domain.PatchArtifact;
import com.contractguard.domain.RemoteRepository;
import com.contractguard.domain.RunFailure;
import com.contractguard.domain.RunState;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Pushes an already-validated remediation branch and opens a draft pull
 * request (FR-027). Publishing is a distinct, explicit human action — never
 * automatic — mirroring the approval gate that guards execution.
 */
public class PublishService {

    private static final String STEP_PUBLISH = "publish";

    private final RunRepository runs;
    private final RunEventLog events;
    private final GitWorkspacePort git;
    private final RemoteGitPort remoteGit;
    private final PullRequestPort pullRequests;
    private final RemoteRepositoryRegistry remoteRepositories;
    private final AuditTrailService audit;
    private final ObservabilityPort observability;
    private final Clock clock;

    // Explicit one-dependency-per-parameter constructor, consistent with this project's
    // hexagonal-architecture style throughout -- no bundling into a config/context object.
    @SuppressWarnings("java:S107")
    public PublishService(RunRepository runs, RunEventLog events, GitWorkspacePort git,
            RemoteGitPort remoteGit, PullRequestPort pullRequests,
            RemoteRepositoryRegistry remoteRepositories, AuditTrailService audit,
            ObservabilityPort observability, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.git = git;
        this.remoteGit = remoteGit;
        this.pullRequests = pullRequests;
        this.remoteRepositories = remoteRepositories;
        this.audit = audit;
        this.observability = observability;
        this.clock = clock;
    }

    /** Precondition check + state move; called synchronously by the API before async publishing. */
    public AnalysisRun beginPublish(String runId) {
        AnalysisRun run = RunLookup.require(runs, runId);
        RemoteRepository remote = requireRemote(run);
        RunState from = run.state();
        run.transitionTo(RunState.PUBLISHING, clock.instant());
        runs.save(run);
        audit.recordTransition(run, from, RunState.PUBLISHING);
        events.append(run.id(), STEP_PUBLISH, "STARTED",
                "Publishing %s to %s/%s".formatted(run.workingBranch(), remote.owner(), remote.name()),
                RunEventLog.KIND_TOOL);
        return run;
    }

    public void publish(String runId) {
        AnalysisRun run = RunLookup.require(runs, runId);
        try (ObservabilityPort.SpanHandle span = startSpan(run, STEP_PUBLISH)) {
            try {
                RemoteRepository remote = requireRemote(run);
                String credential = remoteRepositories.credentialFor(run.repositoryId()).orElseThrow(() ->
                        ContractGuardException.of(FailureCategory.CREDENTIAL_KEY_NOT_CONFIGURED,
                                "no stored credential for repository '%s'".formatted(run.repositoryId()),
                                "Re-register the repository with a credential."));

                commitBranch(run);
                pushBranch(run, remote, credential);
                PullRequestPort.PullRequestResult result = openPullRequest(run, remote, credential);

                RunState from = run.state();
                run.recordPublished(result.url(), clock.instant());
                runs.save(run);
                audit.recordTransition(run, from, RunState.PUBLISHED);
                events.append(run.id(), STEP_PUBLISH, "PR_OPENED",
                        "Draft pull request opened: " + result.url(), RunEventLog.KIND_TOOL);
            } catch (ContractGuardException e) {
                span.recordError(e.getMessage());
                failPublish(run, e.failure());
            } catch (RuntimeException e) {
                span.recordError(e.getMessage());
                failPublish(run, new RunFailure(FailureCategory.INTERNAL_ERROR,
                        "unexpected publish error: " + e.getMessage(), true, null,
                        "Inspect the application logs; the remote branch may already be pushed."));
            }
        }
    }

    private void commitBranch(AnalysisRun run) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, "publish-commit")) {
            git.commit(run.repositoryId(), "ContractGuard: remediate %s (run %s)"
                    .formatted(run.name(), Ids.shortId(run.id())), changedPaths(run));
            events.append(run.id(), STEP_PUBLISH, "COMMITTED",
                    "Committed working branch " + run.workingBranch(), RunEventLog.KIND_TOOL);
            audit.recordRepositoryMutation(run, "Committed working branch " + run.workingBranch());
        }
    }

    /**
     * Exactly the files the applied patch actually wrote — never {@code git add -A}, which
     * would also sweep up incidental working-tree changes unrelated to the approved
     * remediation (e.g. {@code mvnw}'s executable bit, flipped by validation so the wrapper
     * can even run).
     */
    private static Set<String> changedPaths(AnalysisRun run) {
        return run.patches().stream()
                .filter(patch -> patch.checkStatus() == PatchArtifact.CheckStatus.APPLIED)
                .flatMap(patch -> patch.changedPaths().stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private void pushBranch(AnalysisRun run, RemoteRepository remote, String credential) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, "publish-push")) {
            remoteGit.push(run.repositoryId(), run.workingBranch(), remote, credential);
            events.append(run.id(), STEP_PUBLISH, "PUSHED",
                    "Pushed %s to origin".formatted(run.workingBranch()), RunEventLog.KIND_TOOL);
            audit.recordRepositoryMutation(run, "Pushed %s to %s/%s"
                    .formatted(run.workingBranch(), remote.owner(), remote.name()));
        }
    }

    private PullRequestPort.PullRequestResult openPullRequest(AnalysisRun run, RemoteRepository remote,
            String credential) {
        try (ObservabilityPort.SpanHandle span = startSpan(run, "publish-pr")) {
            String body = PullRequestBodyRenderer.render(run, remote);
            PullRequestPort.PullRequestResult result = pullRequests.openDraftPullRequest(
                    new PullRequestPort.PullRequestRequest(remote.owner(), remote.name(), run.workingBranch(),
                            remote.defaultBranch(), "ContractGuard: " + run.name(), body, credential));
            audit.recordRepositoryMutation(run, "Opened draft pull request: " + result.url());
            return result;
        }
    }

    private ObservabilityPort.SpanHandle startSpan(AnalysisRun run, String name) {
        return observability.startSpan(name, Map.of("runId", run.id(), "repositoryId", run.repositoryId()));
    }

    private RemoteRepository requireRemote(AnalysisRun run) {
        return remoteRepositories.find(run.repositoryId()).orElseThrow(() ->
                ContractGuardException.of(FailureCategory.REMOTE_REPOSITORY_NOT_REGISTERED,
                        "repository '%s' is not registered for remote publishing".formatted(run.repositoryId()),
                        "Register the repository via POST /api/repositories/remote first."));
    }

    private void failPublish(AnalysisRun run, RunFailure failure) {
        if (run.state() == RunState.PUBLISHING) {
            run.recordPublishFailure(failure, clock.instant());
            runs.save(run);
            audit.recordTransition(run, RunState.PUBLISHING, RunState.PUBLISH_FAILED);
        }
        events.append(run.id(), STEP_PUBLISH, "FAILED",
                "%s: %s".formatted(failure.category(), failure.message()), RunEventLog.KIND_SYSTEM);
    }
}
