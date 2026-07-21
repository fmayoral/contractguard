package com.contractguard.application.service;

import com.contractguard.application.policy.RepositoryLock;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RemoteRepository;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunServiceTest {

    @TempDir
    Path workspace;

    @TempDir
    Path specsDir;

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private RunService service;
    private final List<Runnable> submitted = new ArrayList<>();

    /** No pipeline (null), no queue bound (0 = unbounded) unless a test overrides it. */
    private RunService newService(RemoteRepositoryRegistry registry, WorkspacePolicy policy,
            RepositoryLock lock, AnalysisPipeline pipeline, int maxActiveRuns) {
        RemoteRepositoryService remoteRepositories =
                new RemoteRepositoryService(registry, (RemoteGitPort) null, Clock.systemUTC());
        return new RunService(runs, events, policy, remoteRepositories, lock, pipeline,
                specsDir, submitted::add, maxActiveRuns, Clock.systemUTC());
    }

    private static RemoteRepositoryRegistry noRemotesRegistry() {
        return new RemoteRepositoryRegistry() {
            @Override
            public void register(RemoteRepository repository, String token) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<RemoteRepository> find(String repositoryId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> credentialFor(String repositoryId) {
                return Optional.empty();
            }

            @Override
            public List<RemoteRepository> findAll() {
                return List.of();
            }
        };
    }

    /** Records the run IDs it was asked to analyse instead of actually running the pipeline. */
    static class TrackingPipeline extends AnalysisPipeline {
        final List<String> analysed = new ArrayList<>();

        TrackingPipeline() {
            super(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void analyse(String runId, Path oldSpec, Path newSpec) {
            analysed.add(runId);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(workspace.resolve("customer-consumer/.git"));
        Files.writeString(specsDir.resolve("old.yaml"), "openapi: 3.0.3");
        Files.writeString(specsDir.resolve("new.yaml"), "openapi: 3.0.3");
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        // No remote repositories registered: ensureLocalClone must be a no-op for local workspace runs.
        // The executor records instead of running: creation must not block on analysis.
        service = newService(noRemotesRegistry(), new WorkspacePolicy(List.of(workspace)),
                new RepositoryLock(), null, 0);
    }

    @Test
    void createsRunAndSchedulesAnalysis() {
        AnalysisRun run = service.createRun("demo run", "customer-consumer", "old.yaml", "new.yaml");

        assertThat(run.state()).isEqualTo(RunState.CREATED);
        assertThat(run.name()).isEqualTo("demo run");
        assertThat(run.oldSpecFile()).isEqualTo("old.yaml");
        assertThat(run.newSpecFile()).isEqualTo("new.yaml");
        assertThat(runs.findById(run.id())).isPresent();
        assertThat(submitted).hasSize(1);
        assertThat(events.all()).anySatisfy(event ->
                assertThat(event.status()).isEqualTo("CREATED"));
    }

    @Test
    void blankNameGetsAGeneratedOne() {
        AnalysisRun run = service.createRun(" ", "customer-consumer", "old.yaml", "new.yaml");
        assertThat(run.name()).startsWith("run-");
    }

    @Test
    void remoteRegisteredRepositoriesAreListedSeparatelyFromLocalOnes() throws IOException {
        Files.createDirectories(workspace.resolve("acme-widgets/.git"));
        RemoteRepository registered = RemoteRepository.forGitHub(
                "acme-widgets", "https://github.com/acme/widgets", "main", Fixtures.T0);
        RemoteRepositoryRegistry oneRemote = new RemoteRepositoryRegistry() {
            @Override
            public void register(RemoteRepository repository, String token) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<RemoteRepository> find(String repositoryId) {
                return "acme-widgets".equals(repositoryId) ? Optional.of(registered) : Optional.empty();
            }

            @Override
            public Optional<String> credentialFor(String repositoryId) {
                return Optional.empty();
            }

            @Override
            public List<RemoteRepository> findAll() {
                return List.of(registered);
            }
        };
        RunService withRemote = newService(oneRemote, new WorkspacePolicy(List.of(workspace)),
                new RepositoryLock(), null, 0);

        // "acme-widgets" was already cloned (it has a local .git dir under the workspace root too),
        // but it must appear only in the remote list, never duplicated into the local one.
        assertThat(withRemote.listRepositories()).containsExactly("customer-consumer");
        assertThat(withRemote.listRemoteRepositories()).containsExactly("acme-widgets");
    }

    @Test
    void specNamesWithPathTricksAreRejected() {
        for (String evil : new String[] {"../old.yaml", "a/b.yaml", "a\\b.yaml", "", null}) {
            assertThatThrownBy(() -> service.createRun("x", "customer-consumer", evil, "new.yaml"))
                    .as(String.valueOf(evil))
                    .isInstanceOf(ContractGuardException.class);
        }
    }

    @Test
    void missingSpecFileIsRejected() {
        assertThatThrownBy(() -> service.createRun("x", "customer-consumer", "ghost.yaml", "new.yaml"))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void unknownRepositoryIsRejected() {
        assertThatThrownBy(() -> service.createRun("x", "ghost-repo", "old.yaml", "new.yaml"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.REPOSITORY_OUTSIDE_WORKSPACE));
    }

    @Test
    void busyRepositoryIsRejected() {
        service.createRun("first", "customer-consumer", "old.yaml", "new.yaml");
        assertThatThrownBy(() -> service.createRun("second", "customer-consumer", "old.yaml", "new.yaml"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.REPOSITORY_BUSY));
    }

    @Test
    void concurrentCreationForTheSameRepositoryIsRejectedByTheLock() {
        // Simulates a second request landing while the first is still inside its
        // check-then-insert window — the DB-level busy check alone cannot catch this (FR-032).
        RepositoryLock lock = new RepositoryLock();
        lock.tryAcquire("customer-consumer");
        RunService locked = newService(noRemotesRegistry(), new WorkspacePolicy(List.of(workspace)),
                lock, null, 0);

        assertThatThrownBy(() -> locked.createRun("x", "customer-consumer", "old.yaml", "new.yaml"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.REPOSITORY_BUSY));
        assertThat(runs.findAll()).isEmpty();
    }

    @Test
    void lockIsReleasedAfterCreationSoASecondDifferentRunCanProceed() {
        service.createRun("first", "customer-consumer", "old.yaml", "new.yaml");
        // A later, unrelated run against the same repository must still hit the ordinary
        // DB-level REPOSITORY_BUSY check, not a permanently-held lock.
        assertThatThrownBy(() -> service.createRun("second", "customer-consumer", "old.yaml", "new.yaml"))
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.REPOSITORY_BUSY));
    }

    @Test
    void queueFullRejectsNewRunsAcrossDifferentRepositories() throws IOException {
        Files.createDirectories(workspace.resolve("other-consumer/.git"));
        RunService bounded = newService(noRemotesRegistry(), new WorkspacePolicy(List.of(workspace)),
                new RepositoryLock(), null, 1);

        bounded.createRun("first", "customer-consumer", "old.yaml", "new.yaml");

        assertThatThrownBy(() -> bounded.createRun("second", "other-consumer", "old.yaml", "new.yaml"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.RUN_QUEUE_FULL));
    }

    @Test
    void zeroMaxActiveRunsMeansUnbounded() throws IOException {
        Files.createDirectories(workspace.resolve("other-consumer/.git"));
        // service (from setUp) was built with maxActiveRuns = 0: a second active run against a
        // different, valid repository must succeed with no capacity rejection at all.
        service.createRun("first", "customer-consumer", "old.yaml", "new.yaml");

        AnalysisRun second = service.createRun("second", "other-consumer", "old.yaml", "new.yaml");

        assertThat(second.state()).isEqualTo(RunState.CREATED);
        assertThat(runs.countActive()).isEqualTo(2);
    }

    @Test
    void listsSpecificationFiles() {
        assertThat(service.listSpecificationFiles()).containsExactly("new.yaml", "old.yaml");
        assertThat(service.listRepositories()).containsExactly("customer-consumer");
    }

    @Test
    void interruptedMidPipelineRunsAreFailedOnStartup() {
        AnalysisRun diffing = Fixtures.newRun();
        diffing.transitionTo(RunState.VALIDATING_INPUT, Fixtures.T0);
        diffing.transitionTo(RunState.DIFFING, Fixtures.T0);
        runs.save(diffing);
        AnalysisRun done = new AnalysisRun("run-done", "d", "customer-consumer", "t", Fixtures.T0);
        done.markCancelled(Fixtures.T0);
        runs.save(done);

        RunService.InterruptedRunRecovery recovery = service.failInterruptedRuns();

        assertThat(recovery.failed()).isEqualTo(1);
        assertThat(recovery.resumed()).isZero();
        assertThat(runs.findById(diffing.id()).orElseThrow().state()).isEqualTo(RunState.FAILED);
        assertThat(runs.findById("run-done").orElseThrow().state()).isEqualTo(RunState.CANCELLED);
    }

    @Test
    void interruptedAwaitingApprovalRunsAreLeftUntouched() {
        AnalysisRun awaiting = Fixtures.runAwaitingApproval();
        runs.save(awaiting);

        RunService.InterruptedRunRecovery recovery = service.failInterruptedRuns();

        assertThat(recovery.resumed()).isZero();
        assertThat(recovery.failed()).isZero();
        assertThat(runs.findById(awaiting.id()).orElseThrow().state()).isEqualTo(RunState.AWAITING_APPROVAL);
    }

    @Test
    void interruptedRunsStillCreatedAreResumed() {
        TrackingPipeline pipeline = new TrackingPipeline();
        RunService withPipeline = newService(noRemotesRegistry(), new WorkspacePolicy(List.of(workspace)),
                new RepositoryLock(), pipeline, 0);
        AnalysisRun created = new AnalysisRun("run-created", "c", "customer-consumer", "t", Fixtures.T0,
                "old.yaml", "new.yaml");
        runs.save(created);

        RunService.InterruptedRunRecovery recovery = withPipeline.failInterruptedRuns();

        assertThat(recovery.resumed()).isEqualTo(1);
        assertThat(recovery.failed()).isZero();
        assertThat(submitted).hasSize(1);
        submitted.get(0).run();
        assertThat(pipeline.analysed).containsExactly("run-created");
        assertThat(runs.findById("run-created").orElseThrow().state()).isEqualTo(RunState.CREATED);
    }

    @Test
    void interruptedCreatedRunWithAMissingSpecFileFailsCleanly() {
        AnalysisRun created = new AnalysisRun("run-created", "c", "customer-consumer", "t", Fixtures.T0,
                "ghost.yaml", "new.yaml");
        runs.save(created);

        RunService.InterruptedRunRecovery recovery = service.failInterruptedRuns();

        assertThat(recovery.resumed()).isZero();
        assertThat(recovery.failed()).isEqualTo(1);
        assertThat(runs.findById("run-created").orElseThrow().state()).isEqualTo(RunState.FAILED);
    }
}
