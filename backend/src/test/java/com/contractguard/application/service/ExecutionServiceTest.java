package com.contractguard.application.service;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.adapter.notification.NoOpNotificationPort;
import com.contractguard.application.agent.ImplementationAgent;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.BuildValidationPort;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.PatchPort;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.AuditEventType;
import com.contractguard.domain.PatchArtifact;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryAuditTrail;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import com.contractguard.testsupport.NoOpObservability;
import com.contractguard.testsupport.QueuedLlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionServiceTest {

    private static final String REWRITE_RESPONSE = """
            {"files":[{"path":"src/main/java/App.java","newContent":"String displayName;"}],
             "notes":"renamed"}""";

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private InMemoryAuditTrail audit;
    private QueuedLlmGateway gateway;
    private FakeGit fakeGit;
    private FakePatch fakePatch;
    private Deque<BuildValidationPort.BuildResult> buildResults;
    private Map<String, String> savedArtifacts;
    private ExecutionService service;

    static class FakeGit implements GitWorkspacePort {
        boolean clean = true;
        boolean branchTaken = false;
        List<String> createdBranches = new ArrayList<>();

        @Override
        public GitStatus status(String repositoryId) {
            return new GitStatus("main", clean, clean ? List.of() : List.of("M App.java"));
        }

        @Override
        public boolean branchExists(String repositoryId, String branchName) {
            return branchTaken;
        }

        @Override
        public void createBranch(String repositoryId, String branchName) {
            createdBranches.add(branchName);
        }

        @Override
        public void commit(String repositoryId, String message) {
            // not exercised by ExecutionService; FR-027 publish flow has its own tests
        }
    }

    static class FakePatch implements PatchPort {
        List<String> changedPaths = List.of("src/main/java/App.java");
        List<String> rejections = List.of();
        List<String> appliedDiffs = new ArrayList<>();
        String diffContent = "--- a/src/main/java/App.java\n+++ b/src/main/java/App.java\n"
                + "@@ -1 +1 @@\n-String fullName;\n+String displayName;\n";

        @Override
        public String buildUnifiedDiff(String repositoryId, Map<String, String> newContents) {
            return diffContent;
        }

        @Override
        public PatchCheck check(String repositoryId, String unifiedDiff) {
            return new PatchCheck(rejections.isEmpty(), changedPaths, rejections, 1, 1);
        }

        @Override
        public void apply(String repositoryId, String unifiedDiff) {
            appliedDiffs.add(unifiedDiff);
        }
    }

    @BeforeEach
    void setUp() {
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        audit = new InMemoryAuditTrail();
        gateway = new QueuedLlmGateway();
        fakeGit = new FakeGit();
        fakePatch = new FakePatch();
        buildResults = new ArrayDeque<>();
        savedArtifacts = new HashMap<>();

        JacksonJsonCodec codec = new JacksonJsonCodec();
        SourceReaderPort reader = (repositoryId, path, start, end) ->
                new SourceReaderPort.FileContent(path, "String fullName;", 1, 1, 1, false);
        BuildValidationPort builds = (repositoryId, commandKey) -> {
            if (buildResults.isEmpty()) {
                throw new IllegalStateException("no scripted build result left");
            }
            return buildResults.poll();
        };
        ArtifactStore artifacts = new ArtifactStore() {
            @Override
            public String save(String runId, String name, String content) {
                savedArtifacts.put(name, content);
                return name;
            }

            @Override
            public Optional<String> read(String runId, String artifactId) {
                return Optional.ofNullable(savedArtifacts.get(artifactId));
            }

            @Override
            public void deleteForRun(String runId) {
                savedArtifacts.clear();
            }
        };
        service = new ExecutionService(runs, events, fakeGit, fakePatch, builds, reader,
                new ImplementationAgent(new LlmJsonClient(gateway, codec, new NoOpObservability()), new PromptLibrary(), codec),
                artifacts, "maven-verify",
                new AuditTrailService(audit, new NoOpNotificationPort(), "test-operator", Clock.systemUTC()),
                new NoOpObservability(), Clock.systemUTC());
    }

    private AnalysisRun approvedRun() {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        run.recordApproval(new Approval(run.id(), run.plan().orElseThrow().hash(),
                Approval.Decision.APPROVED, Fixtures.T0), Fixtures.T0);
        runs.save(run);
        return run;
    }

    private static BuildValidationPort.BuildResult build(int exitCode) {
        String output = exitCode == 0
                ? "[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS"
                : "[ERROR] COMPILATION ERROR : \n[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0"
                        + "\n[INFO] BUILD FAILURE";
        return new BuildValidationPort.BuildResult(exitCode, Duration.ofSeconds(30), output, false, false);
    }

    @Test
    void happyPathPatchesValidatesAndSucceeds() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE);
        buildResults.add(build(0));

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(result.originalBranch()).isEqualTo("main");
        assertThat(result.workingBranch()).startsWith("contractguard/run-");
        assertThat(result.patches()).singleElement().satisfies(patch -> {
            assertThat(patch.checkStatus()).isEqualTo(PatchArtifact.CheckStatus.APPLIED);
            assertThat(patch.attempt()).isEqualTo(1);
        });
        assertThat(result.validations()).singleElement().satisfies(validation -> {
            assertThat(validation.successful()).isTrue();
            assertThat(validation.summary()).contains("BUILD SUCCESS").contains("Tests run: 4");
        });
        assertThat(fakePatch.appliedDiffs).hasSize(1);
        assertThat(savedArtifacts).containsKeys("patch-attempt-1.diff", "validation-attempt-1.log");

        List<AuditEventType> auditedTypes = audit.findByRun(run.id()).stream()
                .map(com.contractguard.domain.AuditEntry::eventType).toList();
        assertThat(auditedTypes).contains(AuditEventType.STATE_TRANSITION, AuditEventType.REPOSITORY_MUTATION);
        assertThat(audit.findByRun(run.id())).anySatisfy(entry ->
                assertThat(entry.detail()).contains("PATCHING"));
        assertThat(audit.findByRun(run.id())).anySatisfy(entry ->
                assertThat(entry.detail()).containsIgnoringCase("branch"));
    }

    @Test
    void executionWithoutApprovalIsImpossible() {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        runs.save(run);

        assertThatThrownBy(() -> service.beginExecution(run.id()))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.ILLEGAL_STATE));
        assertThat(fakeGit.createdBranches).isEmpty();
    }

    @Test
    void dirtyRepositoryBlocksExecutionBeforeAnyMutation() {
        AnalysisRun run = approvedRun();
        fakeGit.clean = false;

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.state()).isEqualTo(RunState.FAILED);
        assertThat(result.failure().orElseThrow().category()).isEqualTo(FailureCategory.DIRTY_REPOSITORY);
        assertThat(result.failure().orElseThrow().mutationOccurred()).isFalse();
        assertThat(fakeGit.createdBranches).isEmpty();
        assertThat(fakePatch.appliedDiffs).isEmpty();
    }

    @Test
    void conflictingBranchBlocksExecution() {
        AnalysisRun run = approvedRun();
        fakeGit.branchTaken = true;

        service.beginExecution(run.id());
        service.execute(run.id());

        assertThat(runs.findById(run.id()).orElseThrow().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.POLICY_VIOLATION);
    }

    @Test
    void patchTouchingUnapprovedFilesIsRejected() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE);
        fakePatch.changedPaths = List.of("src/main/java/App.java", "pom.xml");

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.state()).isEqualTo(RunState.FAILED);
        assertThat(result.failure().orElseThrow().category()).isEqualTo(FailureCategory.PATCH_REJECTED);
        assertThat(fakePatch.appliedDiffs).isEmpty();
    }

    @Test
    void invalidPatchIsNeverApplied() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE);
        fakePatch.rejections = List.of("git apply --check failed: corrupt");

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.failure().orElseThrow().category()).isEqualTo(FailureCategory.PATCH_REJECTED);
        assertThat(fakePatch.appliedDiffs).isEmpty();
    }

    @Test
    void patchContainingSecretsIsRejected() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE);
        fakePatch.diffContent = "+api_key=sk-live-abcdefghijklmnop1234\n";

        service.beginExecution(run.id());
        service.execute(run.id());

        assertThat(runs.findById(run.id()).orElseThrow().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.PATCH_REJECTED);
        assertThat(fakePatch.appliedDiffs).isEmpty();
    }

    @Test
    void failedValidationTriggersExactlyOneRepairThenSucceeds() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE, REWRITE_RESPONSE);
        buildResults.add(build(1));
        buildResults.add(build(0));

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(result.validations()).hasSize(2);
        assertThat(result.patches()).hasSize(2);
        // Repair prompt received the failure output.
        assertThat(gateway.requests().get(1).promptName()).isEqualTo("repair-agent");
        assertThat(gateway.requests().get(1).userPayload()).contains("COMPILATION ERROR");
    }

    @Test
    void secondValidationFailureEndsTheRunNoThirdAttempt() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE, REWRITE_RESPONSE);
        buildResults.add(build(1));
        buildResults.add(build(1));

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.state()).isEqualTo(RunState.FAILED);
        assertThat(result.failure().orElseThrow().category()).isEqualTo(FailureCategory.VALIDATION_FAILURE);
        assertThat(result.failure().orElseThrow().mutationOccurred()).isTrue();
        assertThat(result.validations()).hasSize(2);
        assertThat(gateway.requests()).hasSize(2);
        assertThat(buildResults).isEmpty();
    }

    @Test
    void buildTimeoutFailsTyped() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE);
        buildResults.add(new BuildValidationPort.BuildResult(-1, Duration.ofMinutes(15),
                "partial output", true, true));

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.failure().orElseThrow().category()).isEqualTo(FailureCategory.BUILD_TIMEOUT);
        assertThat(result.failure().orElseThrow().mutationOccurred()).isTrue();
    }

    @Test
    void deterministicRewritesLandEvenWhenTheModelProposesNothingFurther() {
        // The fixture change (PROPERTY_RENAMED fullName->displayName) matches the reader's
        // "String fullName;" content, so the mechanical pre-transform alone fully solves this
        // plan -- the model is allowed to (and here does) come back with an empty proposal.
        AnalysisRun run = approvedRun();
        gateway.enqueue("{\"files\":[],\"notes\":\"nothing further needed\"}");
        buildResults.add(build(0));

        service.beginExecution(run.id());
        service.execute(run.id());

        AnalysisRun result = runs.findById(run.id()).orElseThrow();
        assertThat(result.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(fakePatch.appliedDiffs).hasSize(1);
    }

    @Test
    void deterministicPreTransformIsRecordedOnTheTimeline() {
        AnalysisRun run = approvedRun();
        gateway.enqueue(REWRITE_RESPONSE);
        buildResults.add(build(0));

        service.beginExecution(run.id());
        service.execute(run.id());

        // Both approved files (App.java and its test) contain "fullName", so both are fixed.
        assertThat(events.eventsAfter(run.id(), 0)).anySatisfy(event -> {
            assertThat(event.step()).isEqualTo("patch");
            assertThat(event.status()).isEqualTo("MECHANICAL");
            assertThat(event.message()).contains("deterministic fixes to 2 file");
        });
    }

    @Test
    void summariesExtractTestCountsAndCompilationFailures() {
        assertThat(ExecutionService.summarise(build(0)))
                .contains("BUILD SUCCESS").contains("Tests run: 4");
        assertThat(ExecutionService.summarise(build(1)))
                .contains("BUILD FAILURE").contains("compilation failed");
        assertThat(ExecutionService.summarise(new BuildValidationPort.BuildResult(
                0, Duration.ofSeconds(1), "no test lines", true, false)))
                .contains("no test summary found").contains("(output truncated)");
    }
}
