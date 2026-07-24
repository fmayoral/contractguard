package com.contractguard.application.service;

import com.contractguard.adapter.notification.NoOpNotificationPort;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.PullRequestPort;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.AuditEventType;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RemoteRepository;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryAuditTrail;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import com.contractguard.testsupport.NoOpObservability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);
    private static final RemoteRepository REMOTE = RemoteRepository.forGitHub(
            "customer-consumer", "https://github.com/acme/widgets", "main", Instant.parse("2026-07-01T00:00:00Z"));

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private InMemoryAuditTrail audit;
    private List<String> commits;
    private List<Set<String>> committedPaths;
    private List<String> pushedBranches;
    private PullRequestPort.PullRequestResult prResult;
    private RuntimeException pushFailure;
    private RuntimeException prFailure;
    private Map<String, RemoteRepository> registrations;
    private PublishService service;

    @BeforeEach
    void setUp() {
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        audit = new InMemoryAuditTrail();
        commits = new ArrayList<>();
        committedPaths = new ArrayList<>();
        pushedBranches = new ArrayList<>();
        prResult = new PullRequestPort.PullRequestResult("https://github.com/acme/widgets/pull/7", 7);
        pushFailure = null;
        prFailure = null;
        registrations = new HashMap<>();
        registrations.put("customer-consumer", REMOTE);

        GitWorkspacePort git = new GitWorkspacePort() {
            @Override
            public GitStatus status(String repositoryId) {
                return new GitStatus("main", true, List.of());
            }

            @Override
            public boolean branchExists(String repositoryId, String branchName) {
                return false;
            }

            @Override
            public void createBranch(String repositoryId, String branchName) {
                // not exercised here
            }

            @Override
            public void commit(String repositoryId, String message, Set<String> paths) {
                commits.add(message);
                committedPaths.add(paths);
            }
        };
        RemoteGitPort pushingRemoteGit = new RemoteGitPort() {
            @Override
            public void cloneOrRefresh(String repositoryId, RemoteRepository remote, String credential) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void push(String repositoryId, String branchName, RemoteRepository remote, String credential) {
                if (pushFailure != null) {
                    throw pushFailure;
                }
                pushedBranches.add(branchName);
            }

            @Override
            public void deleteLocalClone(String repositoryId) {
                throw new UnsupportedOperationException();
            }
        };
        PullRequestPort pullRequests = request -> {
            if (prFailure != null) {
                throw prFailure;
            }
            return prResult;
        };
        RemoteRepositoryRegistry registry = new RemoteRepositoryRegistry() {
            @Override
            public void register(RemoteRepository repository, String token) {
                registrations.put(repository.repositoryId(), repository);
            }

            @Override
            public Optional<RemoteRepository> find(String repositoryId) {
                return Optional.ofNullable(registrations.get(repositoryId));
            }

            @Override
            public Optional<String> credentialFor(String repositoryId) {
                return registrations.containsKey(repositoryId) ? Optional.of("gh-token") : Optional.empty();
            }

            @Override
            public List<RemoteRepository> findAll() {
                return List.copyOf(registrations.values());
            }

            @Override
            public void deregister(String repositoryId) {
                registrations.remove(repositoryId);
            }
        };
        service = new PublishService(runs, events, git, pushingRemoteGit, pullRequests, registry,
                new AuditTrailService(audit, new NoOpNotificationPort(), "test-operator", CLOCK),
                new NoOpObservability(), CLOCK);
    }

    @Test
    void publishesASucceededRunAndOpensADraftPullRequest() {
        AnalysisRun run = Fixtures.runSucceeded();
        runs.save(run);

        service.beginPublish(run.id());
        service.publish(run.id());

        AnalysisRun published = runs.findById(run.id()).orElseThrow();
        assertThat(published.state()).isEqualTo(RunState.PUBLISHED);
        assertThat(published.pullRequestUrl()).contains("https://github.com/acme/widgets/pull/7");
        assertThat(commits).hasSize(1);
        assertThat(committedPaths).containsExactly(Set.of("src/main/java/App.java"));
        assertThat(pushedBranches).containsExactly(run.workingBranch());
        assertThat(events.all()).anySatisfy(event -> assertThat(event.status()).isEqualTo("PR_OPENED"));

        List<AuditEventType> auditedTypes = audit.findByRun(run.id()).stream()
                .map(com.contractguard.domain.AuditEntry::eventType).toList();
        assertThat(auditedTypes).contains(AuditEventType.STATE_TRANSITION, AuditEventType.REPOSITORY_MUTATION);
        assertThat(audit.findByRun(run.id())).anySatisfy(entry ->
                assertThat(entry.detail()).contains("pull/7"));
    }

    @Test
    void beginPublishRejectsRunsForUnregisteredRepositories() {
        AnalysisRun run = Fixtures.runSucceeded();
        AnalysisRun otherRepo = AnalysisRun.rehydrate(run.id(), run.name(),
                "not-registered", run.traceId(), run.createdAt(), run.updatedAt(), run.state(),
                run.oldSpecFile(), run.newSpecFile(),
                run.oldSpecName(), run.newSpecName(), run.oldSpecHash(), run.newSpecHash(),
                run.originalBranch(), run.workingBranch(), null, null,
                run.changes(), run.evidence(), run.assessments(), run.plan().orElse(null),
                run.approval().orElse(null), run.patches(), run.validations());
        runs.save(otherRepo);
        String otherRepoRunId = otherRepo.id();

        assertThatThrownBy(() -> service.beginPublish(otherRepoRunId))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.REMOTE_REPOSITORY_NOT_REGISTERED));
    }

    @Test
    void pushFailureLeavesTheRunPublishFailedAndRetryable() {
        AnalysisRun run = Fixtures.runSucceeded();
        runs.save(run);
        pushFailure = ContractGuardException.of(FailureCategory.REMOTE_GIT_FAILURE, "push rejected", "retry");

        service.beginPublish(run.id());
        service.publish(run.id());

        AnalysisRun failed = runs.findById(run.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(RunState.PUBLISH_FAILED);
        assertThat(failed.failure()).isPresent();

        // Retry succeeds once the transient push failure is gone.
        pushFailure = null;
        service.beginPublish(run.id());
        service.publish(run.id());
        assertThat(runs.findById(run.id()).orElseThrow().state()).isEqualTo(RunState.PUBLISHED);
    }

    @Test
    void pullRequestFailureAfterPushIsRecordedAsPublishFailed() {
        AnalysisRun run = Fixtures.runSucceeded();
        runs.save(run);
        prFailure = ContractGuardException.of(FailureCategory.PULL_REQUEST_FAILED, "422", "check scopes");

        service.beginPublish(run.id());
        service.publish(run.id());

        AnalysisRun failed = runs.findById(run.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(RunState.PUBLISH_FAILED);
        assertThat(pushedBranches).hasSize(1);
    }
}
