package com.contractguard.application.service;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Fixtures;
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

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(workspace.resolve("customer-consumer/.git"));
        Files.writeString(specsDir.resolve("old.yaml"), "openapi: 3.0.3");
        Files.writeString(specsDir.resolve("new.yaml"), "openapi: 3.0.3");
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        // The executor records instead of running: creation must not block on analysis.
        service = new RunService(runs, events, new WorkspacePolicy(List.of(workspace)),
                null, specsDir, submitted::add, Clock.systemUTC());
    }

    @Test
    void createsRunAndSchedulesAnalysis() {
        AnalysisRun run = service.createRun("demo run", "customer-consumer", "old.yaml", "new.yaml");

        assertThat(run.state()).isEqualTo(RunState.CREATED);
        assertThat(run.name()).isEqualTo("demo run");
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
    void listsSpecificationFiles() {
        assertThat(service.listSpecificationFiles()).containsExactly("new.yaml", "old.yaml");
        assertThat(service.listRepositories()).containsExactly("customer-consumer");
    }

    @Test
    void interruptedActiveRunsAreFailedOnStartup() {
        AnalysisRun active = Fixtures.runAwaitingApproval();
        runs.save(active);
        AnalysisRun done = new AnalysisRun("run-done", "d", "customer-consumer", "t", Fixtures.T0);
        done.markCancelled(Fixtures.T0);
        runs.save(done);

        int failed = service.failInterruptedRuns();

        assertThat(failed).isEqualTo(1);
        assertThat(runs.findById(active.id()).orElseThrow().state()).isEqualTo(RunState.FAILED);
        assertThat(runs.findById("run-done").orElseThrow().state()).isEqualTo(RunState.CANCELLED);
    }
}
