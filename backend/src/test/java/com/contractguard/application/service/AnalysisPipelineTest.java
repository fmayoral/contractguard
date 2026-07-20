package com.contractguard.application.service;

import com.contractguard.adapter.diff.SwaggerOpenApiDiffAdapter;
import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.adapter.llm.ScriptedLlmGateway;
import com.contractguard.adapter.search.BoundedSourceReaderAdapter;
import com.contractguard.adapter.search.FilesystemRepositorySearchAdapter;
import com.contractguard.application.agent.ChangeExplainer;
import com.contractguard.application.agent.ImpactInvestigator;
import com.contractguard.application.agent.MigrationPlanner;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.AuditEventType;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryAuditTrail;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic end-to-end analysis over the bundled sample specs and a
 * materialised copy of the sample consumer — the complete §9 nodes 1-5 flow
 * with the scripted gateway, no Spring, no network.
 */
class AnalysisPipelineTest {

    private static final Path OLD_SPEC = Path.of("../samples/openapi/customer-api-v1.yaml");
    private static final Path NEW_SPEC = Path.of("../samples/openapi/customer-api-v2.yaml");
    private static final Path CONSUMER_TEMPLATE = Path.of("../samples/customer-consumer");

    @TempDir
    Path workspace;

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private InMemoryAuditTrail audit;
    private AnalysisPipeline pipeline;

    @BeforeEach
    void setUp() throws IOException {
        copyTree(CONSUMER_TEMPLATE, workspace.resolve("customer-consumer"));
        Files.createDirectories(workspace.resolve("customer-consumer/.git"));

        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        audit = new InMemoryAuditTrail();
        WorkspacePolicy policy = new WorkspacePolicy(List.of(workspace));
        JacksonJsonCodec codec = new JacksonJsonCodec();
        PromptLibrary prompts = new PromptLibrary();
        LlmJsonClient client = new LlmJsonClient(new ScriptedLlmGateway(), codec);
        FilesystemRepositorySearchAdapter search = new FilesystemRepositorySearchAdapter(policy);
        Clock clock = Clock.systemUTC();
        pipeline = new AnalysisPipeline(runs, events, policy, new SwaggerOpenApiDiffAdapter(),
                new EvidenceCollector(search),
                new ChangeExplainer(client, prompts, codec),
                new ImpactInvestigator(client, prompts, codec, search,
                        new BoundedSourceReaderAdapter(policy), 10),
                new MigrationPlanner(client, prompts, codec, policy, List.of("maven-verify"), clock),
                codec, new AuditTrailService(audit, "test-operator", clock), clock);
    }

    private AnalysisRun newRun() {
        AnalysisRun run = new AnalysisRun("run-1", "demo", "customer-consumer", "trace-1", Instant.now());
        runs.save(run);
        return run;
    }

    @Test
    void analysesTheSeededScenarioToAwaitingApproval() {
        newRun();
        pipeline.analyse("run-1", OLD_SPEC, NEW_SPEC);

        AnalysisRun run = runs.findById("run-1").orElseThrow();
        assertThat(run.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(run.failure()).isEmpty();

        assertThat(run.changes()).hasSize(4);
        assertThat(run.changes()).allSatisfy(change ->
                assertThat(change.explanation()).isNotBlank());
        assertThat(run.oldSpecHash()).isNotBlank();
        assertThat(run.oldSpecName()).isEqualTo("customer-api-v1.yaml");

        // Evidence covers client, DTO, enum policy, tests and docs.
        assertThat(run.evidence()).isNotEmpty();
        assertThat(run.evidence()).extracting(e -> e.relativePath())
                .contains(
                        "src/main/java/com/example/customerapp/CustomerClient.java",
                        "src/main/java/com/example/customerapp/Customer.java",
                        "src/main/java/com/example/customerapp/OrderEligibilityPolicy.java",
                        "src/test/java/com/example/customerapp/OrderEligibilityPolicyTest.java",
                        "README.md");

        // Assessments only for evidenced changes, each citing evidence.
        assertThat(run.assessments()).hasSize(3);
        assertThat(run.assessments()).allSatisfy(assessment ->
                assertThat(assessment.evidenceIds()).isNotEmpty());

        // The plan covers the three breaking changes, tests included.
        assertThat(run.plan()).isPresent();
        assertThat(run.plan().orElseThrow().items()).hasSize(3);
        assertThat(run.plan().orElseThrow().approvedFiles())
                .contains("src/main/java/com/example/customerapp/Customer.java",
                        "src/test/java/com/example/customerapp/CustomerClientTest.java");

        // Timeline shows tool and LLM steps plus the approval pause.
        List<String> steps = events.all().stream().map(e -> e.step()).toList();
        assertThat(steps).contains("input-validation", "diff", "change-explainer",
                "search", "assessment", "planning", "approval");

        // Every state transition on the path to AWAITING_APPROVAL is audited.
        List<String> transitions = audit.findByRun("run-1").stream()
                .filter(entry -> entry.eventType() == AuditEventType.STATE_TRANSITION)
                .map(entry -> entry.detail())
                .toList();
        assertThat(transitions).contains("CREATED -> VALIDATING_INPUT", "DIFFING -> SEARCHING",
                "PLANNING -> AWAITING_APPROVAL");
        assertThat(audit.findByRun("run-1")).allSatisfy(entry ->
                assertThat(entry.principal()).isEqualTo("test-operator"));
    }

    @Test
    void identicalSpecsFailWithNothingToPlan() {
        newRun();
        pipeline.analyse("run-1", OLD_SPEC, OLD_SPEC);

        AnalysisRun run = runs.findById("run-1").orElseThrow();
        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.failure().orElseThrow().category())
                .isIn(FailureCategory.UNSUPPORTED_FEATURE, FailureCategory.INVALID_LLM_RESPONSE);
    }

    @Test
    void missingSpecFailsInInputValidation() {
        newRun();
        pipeline.analyse("run-1", Path.of("nope.yaml"), NEW_SPEC);

        AnalysisRun run = runs.findById("run-1").orElseThrow();
        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.failure().orElseThrow().category()).isEqualTo(FailureCategory.INVALID_OPENAPI);
        assertThat(run.failure().orElseThrow().mutationOccurred()).isFalse();
    }

    @Test
    void busyRepositoryBlocksASecondRun() {
        newRun();
        AnalysisRun second = new AnalysisRun("run-2", "demo2", "customer-consumer", "trace-2", Instant.now());
        runs.save(second);
        pipeline.analyse("run-2", OLD_SPEC, NEW_SPEC);

        assertThat(runs.findById("run-2").orElseThrow().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.REPOSITORY_BUSY);
    }

    @Test
    void rehydratedDemoChangeSetSurvivesPersistenceShape() {
        newRun();
        pipeline.analyse("run-1", OLD_SPEC, NEW_SPEC);
        AnalysisRun run = runs.findById("run-1").orElseThrow();
        assertThat(run.changes()).extracting(c -> c.type()).containsExactlyInAnyOrder(
                ChangeType.ENDPOINT_RENAMED, ChangeType.PROPERTY_RENAMED,
                ChangeType.ENUM_VALUE_REMOVED, ChangeType.PROPERTY_ADDED);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            paths.sorted(Comparator.naturalOrder()).forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
