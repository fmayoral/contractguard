package com.contractguard.adapter.cli;

import com.contractguard.adapter.diff.SwaggerOpenApiDiffAdapter;
import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.adapter.llm.ScriptedLlmGateway;
import com.contractguard.adapter.notification.NoOpNotificationPort;
import com.contractguard.adapter.search.BoundedSourceReaderAdapter;
import com.contractguard.adapter.search.FilesystemRepositorySearchAdapter;
import com.contractguard.application.agent.ChangeExplainer;
import com.contractguard.application.agent.ImpactInvestigator;
import com.contractguard.application.agent.MigrationPlanner;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.policy.RepositoryLock;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.application.service.AnalysisPipeline;
import com.contractguard.application.service.AuditTrailService;
import com.contractguard.application.service.EvidenceCollector;
import com.contractguard.application.service.RemoteRepositoryService;
import com.contractguard.application.service.ReportService;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.RunService;
import com.contractguard.application.service.SpecSourceService;
import com.contractguard.domain.RemoteRepository;
import com.contractguard.testsupport.InMemoryAuditTrail;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import com.contractguard.testsupport.InMemorySpecSourceRegistry;
import com.contractguard.testsupport.NoOpObservability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the CI gate over the bundled demo scenario with the scripted gateway. */
class CliRunnerTest {

    private static final Path SPECS_DIR = Path.of("../samples/openapi");
    private static final Path CONSUMER_TEMPLATE = Path.of("../samples/customer-consumer");

    @TempDir
    Path workspace;

    @TempDir
    Path outputDir;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final Map<String, String> artifacts = new HashMap<>();
    private CliRunner cli;

    @BeforeEach
    void setUp() throws IOException {
        copyTree(CONSUMER_TEMPLATE, workspace.resolve("customer-consumer"));
        Files.createDirectories(workspace.resolve("customer-consumer/.git"));
        Files.createDirectories(workspace.resolve("no-build-tool/.git"));

        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryRunEventLog events = new InMemoryRunEventLog();
        WorkspacePolicy policy = new WorkspacePolicy(List.of(workspace));
        JacksonJsonCodec codec = new JacksonJsonCodec();
        PromptLibrary prompts = new PromptLibrary();
        LlmJsonClient client = new LlmJsonClient(new ScriptedLlmGateway(), codec, new NoOpObservability());
        FilesystemRepositorySearchAdapter search = new FilesystemRepositorySearchAdapter(policy);
        Clock clock = Clock.systemUTC();
        AuditTrailService audit = new AuditTrailService(new InMemoryAuditTrail(), new NoOpNotificationPort(),
                "test-operator", clock);
        AnalysisPipeline pipeline = new AnalysisPipeline(runs, events, policy,
                new SwaggerOpenApiDiffAdapter(),
                new EvidenceCollector(search),
                new ChangeExplainer(client, prompts, codec),
                new ImpactInvestigator(client, prompts, codec, search,
                        new BoundedSourceReaderAdapter(policy), 10),
                new MigrationPlanner(client, prompts, codec, policy, List.of("maven-verify"), clock),
                codec, audit, new NoOpObservability(), clock);

        ArtifactStore artifactStore = new ArtifactStore() {
            @Override
            public String save(String runId, String name, String content) {
                artifacts.put(runId + "/" + name, content);
                return name;
            }

            @Override
            public Optional<String> read(String runId, String artifactId) {
                return Optional.ofNullable(artifacts.get(runId + "/" + artifactId));
            }

            @Override
            public void deleteForRun(String runId) {
                artifacts.keySet().removeIf(key -> key.startsWith(runId + "/"));
            }
        };
        RemoteRepositoryRegistry noRemotes = new RemoteRepositoryRegistry() {
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

            @Override
            public void deregister(String repositoryId) {
                throw new UnsupportedOperationException();
            }
        };
        RemoteRepositoryService remoteRepositories =
                new RemoteRepositoryService(noRemotes, (RemoteGitPort) null, clock);
        SpecSourceService specSources = new SpecSourceService(new InMemorySpecSourceRegistry(),
                (RemoteGitPort) null, SPECS_DIR.resolve("spec-source-cache"), clock);
        RunService runService = new RunService(runs, events, policy, remoteRepositories, specSources,
                new RepositoryLock(), pipeline, SPECS_DIR, SPECS_DIR.resolve("uploads"), Runnable::run, 0, clock);
        cli = new CliRunner(runService,
                new RunQueryService(runs, events, artifactStore),
                new ReportService(runs, events, artifactStore, codec),
                new PrintStream(stdout, true, StandardCharsets.UTF_8));
    }

    @Test
    void breakingChangesTripTheGateAndEmitTheReport() {
        int exit = cli.execute(new CliRunner.Options("customer-consumer",
                "customer-api-v1.yaml", "customer-api-v2.yaml",
                CliRunner.FailOn.BREAKING, null));

        assertThat(exit).isEqualTo(CliRunner.EXIT_GATE_TRIPPED);
        String report = stdout.toString(StandardCharsets.UTF_8);
        assertThat(report).contains("\"state\":\"AWAITING_APPROVAL\"");
        assertThat(report).contains("BREAKING");
        assertThat(report).contains("ENDPOINT_RENAMED");
    }

    @Test
    void gateCanBeDisabled() {
        int exit = cli.execute(new CliRunner.Options("customer-consumer",
                "customer-api-v1.yaml", "customer-api-v2.yaml",
                CliRunner.FailOn.NONE, null));

        assertThat(exit).isEqualTo(CliRunner.EXIT_OK);
    }

    @Test
    void reportCanBeWrittenToAFile() {
        Path report = outputDir.resolve("nested/report.json");

        int exit = cli.execute(new CliRunner.Options("customer-consumer",
                "customer-api-v1.yaml", "customer-api-v2.yaml",
                CliRunner.FailOn.BREAKING, report));

        assertThat(exit).isEqualTo(CliRunner.EXIT_GATE_TRIPPED);
        assertThat(report).exists();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void failedAnalysisReturnsExitOne() {
        int exit = cli.execute(new CliRunner.Options("no-build-tool",
                "customer-api-v1.yaml", "customer-api-v2.yaml",
                CliRunner.FailOn.BREAKING, null));

        assertThat(exit).isEqualTo(CliRunner.EXIT_RUN_FAILED);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("\"state\":\"FAILED\"");
    }

    @Test
    void failOnLevelsParseLeniently() {
        assertThat(CliRunner.FailOn.parse(null)).isEqualTo(CliRunner.FailOn.BREAKING);
        assertThat(CliRunner.FailOn.parse("  ")).isEqualTo(CliRunner.FailOn.BREAKING);
        assertThat(CliRunner.FailOn.parse("none")).isEqualTo(CliRunner.FailOn.NONE);
        assertThat(CliRunner.FailOn.parse("potentially-breaking"))
                .isEqualTo(CliRunner.FailOn.POTENTIALLY_BREAKING);
        assertThat(CliRunner.FailOn.parse("BREAKING")).isEqualTo(CliRunner.FailOn.BREAKING);
    }

    @Test
    void potentiallyBreakingLevelAlsoTripsOnTheDemoScenario() {
        int exit = cli.execute(new CliRunner.Options("customer-consumer",
                "customer-api-v1.yaml", "customer-api-v2.yaml",
                CliRunner.FailOn.POTENTIALLY_BREAKING, null));

        assertThat(exit).isEqualTo(CliRunner.EXIT_GATE_TRIPPED);
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
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
