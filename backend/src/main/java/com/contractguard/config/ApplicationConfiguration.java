package com.contractguard.config;

import com.contractguard.adapter.artifacts.FilesystemArtifactStore;
import com.contractguard.adapter.diff.SwaggerOpenApiDiffAdapter;
import com.contractguard.adapter.git.GitCliAdapter;
import com.contractguard.adapter.git.RemoteGitCliAdapter;
import com.contractguard.adapter.github.GitHubPullRequestAdapter;
import com.contractguard.adapter.process.DockerBuildValidationAdapter;
import com.contractguard.adapter.process.MavenBuildValidationAdapter;
import com.contractguard.adapter.process.ProcessRunner;
import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.adapter.llm.LlmSettings;
import com.contractguard.adapter.llm.OpenAiCompatibleLlmGateway;
import com.contractguard.adapter.llm.ScriptedLlmGateway;
import com.contractguard.adapter.persistence.JdbcAuditTrail;
import com.contractguard.adapter.persistence.JdbcRemoteRepositoryRegistry;
import com.contractguard.adapter.persistence.JdbcRunEventLog;
import com.contractguard.adapter.persistence.JdbcRunRepository;
import com.contractguard.adapter.search.BoundedSourceReaderAdapter;
import com.contractguard.adapter.search.FilesystemRepositorySearchAdapter;
import com.contractguard.adapter.security.AesGcmCredentialCipher;
import com.contractguard.application.agent.ChangeExplainer;
import com.contractguard.application.agent.ImpactInvestigator;
import com.contractguard.application.agent.ImplementationAgent;
import com.contractguard.application.agent.MigrationPlanner;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.policy.RepositoryLock;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.AuditTrailPort;
import com.contractguard.application.port.BuildValidationPort;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.PatchPort;
import com.contractguard.application.port.LlmGateway;
import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.application.port.PullRequestPort;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.application.service.AnalysisPipeline;
import com.contractguard.application.service.ApprovalService;
import com.contractguard.application.service.AuditTrailService;
import com.contractguard.application.service.EvidenceCollector;
import com.contractguard.application.service.ExecutionService;
import com.contractguard.application.service.PublishService;
import com.contractguard.application.service.RemoteRepositoryService;
import com.contractguard.application.service.ReportService;
import com.contractguard.application.service.RetentionService;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Composition root: adapters are chosen and wired to ports here, nowhere else. */
@Configuration
@EnableConfigurationProperties(ContractGuardProperties.class)
public class ApplicationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApplicationConfiguration.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public JsonCodec jsonCodec() {
        return new JacksonJsonCodec();
    }

    @Bean
    public WorkspacePolicy workspacePolicy(ContractGuardProperties properties) {
        // The remote-repository clone cache is itself a workspace root, so once a
        // remote repo is cloned there it is an ordinary local repository to every
        // other adapter — diff, search and execution need no remote-aware branch (ADR-0007).
        List<Path> roots = new ArrayList<>(
                properties.workspace().roots().stream().map(Path::of).toList());
        roots.add(remoteCacheDirectory(properties));
        return new WorkspacePolicy(roots);
    }

    private static Path remoteCacheDirectory(ContractGuardProperties properties) {
        return Path.of(properties.storage().directory()).resolve("remote-cache");
    }

    @Bean
    public OpenApiDiffPort openApiDiffPort() {
        return new SwaggerOpenApiDiffAdapter();
    }

    @Bean
    public RepositorySearchPort repositorySearchPort(WorkspacePolicy policy) {
        return new FilesystemRepositorySearchAdapter(policy);
    }

    @Bean
    public SourceReaderPort sourceReaderPort(WorkspacePolicy policy) {
        return new BoundedSourceReaderAdapter(policy);
    }

    @Bean
    public LlmGateway llmGateway(ContractGuardProperties properties) {
        ContractGuardProperties.Llm llm = properties.llm();
        if ("openai".equalsIgnoreCase(llm.provider())) {
            log.info("LLM provider: OpenAI-compatible endpoint {} (model {})", llm.baseUrl(), llm.model());
            return new OpenAiCompatibleLlmGateway(new LlmSettings(llm.baseUrl(), llm.apiKey(),
                    llm.model(), llm.timeout(), llm.maxTokens(), llm.temperature()));
        }
        log.info("LLM provider: deterministic scripted gateway (mock mode)");
        return new ScriptedLlmGateway();
    }

    @Bean
    public PromptLibrary promptLibrary() {
        return new PromptLibrary();
    }

    @Bean
    public LlmJsonClient llmJsonClient(LlmGateway gateway, JsonCodec codec) {
        return new LlmJsonClient(gateway, codec);
    }

    @Bean
    public ChangeExplainer changeExplainer(LlmJsonClient client, PromptLibrary prompts, JsonCodec codec) {
        return new ChangeExplainer(client, prompts, codec);
    }

    @Bean
    public ImpactInvestigator impactInvestigator(LlmJsonClient client, PromptLibrary prompts,
            JsonCodec codec, RepositorySearchPort searchPort, SourceReaderPort sourceReader,
            ContractGuardProperties properties) {
        return new ImpactInvestigator(client, prompts, codec, searchPort, sourceReader,
                properties.llm().maxWorkflowSteps());
    }

    @Bean
    public MigrationPlanner migrationPlanner(LlmJsonClient client, PromptLibrary prompts,
            JsonCodec codec, WorkspacePolicy policy, ContractGuardProperties properties, Clock clock) {
        return new MigrationPlanner(client, prompts, codec, policy,
                List.of(properties.validation().commandKey()), clock);
    }

    @Bean
    public EvidenceCollector evidenceCollector(RepositorySearchPort searchPort) {
        return new EvidenceCollector(searchPort);
    }

    @Bean
    public RunRepository runRepository(JdbcTemplate jdbc) {
        return new JdbcRunRepository(jdbc);
    }

    @Bean
    public RunEventLog runEventLog(JdbcTemplate jdbc, Clock clock) {
        return new JdbcRunEventLog(jdbc, clock);
    }

    @Bean
    public ArtifactStore artifactStore(ContractGuardProperties properties) {
        return new FilesystemArtifactStore(Path.of(properties.storage().directory()));
    }

    @Bean
    public AuditTrailPort auditTrailPort(JdbcTemplate jdbc) {
        return new JdbcAuditTrail(jdbc);
    }

    @Bean
    public AuditTrailService auditTrailService(AuditTrailPort port, ContractGuardProperties properties,
            Clock clock) {
        return new AuditTrailService(port, properties.audit().defaultPrincipal(), clock);
    }

    @Bean
    public AnalysisPipeline analysisPipeline(RunRepository runs, RunEventLog events,
            WorkspacePolicy policy, OpenApiDiffPort diffPort, EvidenceCollector evidenceCollector,
            ChangeExplainer changeExplainer, ImpactInvestigator investigator,
            MigrationPlanner planner, JsonCodec codec, AuditTrailService audit, Clock clock) {
        return new AnalysisPipeline(runs, events, policy, diffPort, evidenceCollector,
                changeExplainer, investigator, planner, codec, audit, clock);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService analysisExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    @Bean
    public RepositoryLock repositoryLock() {
        return new RepositoryLock();
    }

    @Bean
    public RunService runService(RunRepository runs, RunEventLog events, WorkspacePolicy policy,
            RemoteRepositoryService remoteRepositories, RepositoryLock repositoryLock,
            AnalysisPipeline pipeline, ContractGuardProperties properties,
            ExecutorService analysisExecutor, Clock clock) {
        return new RunService(runs, events, policy, remoteRepositories, repositoryLock, pipeline,
                Path.of(properties.specs().directory()), analysisExecutor,
                properties.concurrency().maxActiveRuns(), clock);
    }

    @Bean
    public AesGcmCredentialCipher credentialCipher(ContractGuardProperties properties) {
        return new AesGcmCredentialCipher(properties.remote().credentialKey());
    }

    @Bean
    public RemoteRepositoryRegistry remoteRepositoryRegistry(JdbcTemplate jdbc, AesGcmCredentialCipher cipher) {
        return new JdbcRemoteRepositoryRegistry(jdbc, cipher);
    }

    @Bean
    public RemoteGitPort remoteGitPort(ProcessRunner processRunner, ContractGuardProperties properties) {
        return new RemoteGitCliAdapter(remoteCacheDirectory(properties), processRunner);
    }

    @Bean
    public PullRequestPort pullRequestPort() {
        return new GitHubPullRequestAdapter();
    }

    @Bean
    public RemoteRepositoryService remoteRepositoryService(RemoteRepositoryRegistry registry,
            RemoteGitPort remoteGit, Clock clock) {
        return new RemoteRepositoryService(registry, remoteGit, clock);
    }

    @Bean
    public PublishService publishService(RunRepository runs, RunEventLog events, GitWorkspacePort git,
            RemoteGitPort remoteGit, PullRequestPort pullRequests, RemoteRepositoryRegistry remoteRepositories,
            AuditTrailService audit, Clock clock) {
        return new PublishService(runs, events, git, remoteGit, pullRequests, remoteRepositories, audit, clock);
    }

    @Bean
    public ApprovalService approvalService(RunRepository runs, RunEventLog events,
            AuditTrailService audit, Clock clock) {
        return new ApprovalService(runs, events, audit, clock);
    }

    @Bean
    public RetentionService retentionService(RunRepository runs, RunEventLog events,
            ArtifactStore artifacts, ContractGuardProperties properties, Clock clock) {
        return new RetentionService(runs, events, artifacts,
                properties.storage().retentionDays(), clock);
    }

    @Bean
    public RunQueryService runQueryService(RunRepository runs, RunEventLog events, ArtifactStore artifacts) {
        return new RunQueryService(runs, events, artifacts);
    }

    @Bean
    public ReportService reportService(RunRepository runs, RunEventLog events,
            ArtifactStore artifacts, JsonCodec codec) {
        return new ReportService(runs, events, artifacts, codec);
    }

    @Bean
    public ProcessRunner processRunner() {
        return new ProcessRunner();
    }

    @Bean
    public GitCliAdapter gitCliAdapter(WorkspacePolicy policy, ProcessRunner processRunner,
            ContractGuardProperties properties) {
        return new GitCliAdapter(policy, processRunner,
                Path.of(properties.storage().directory()).resolve("scratch"));
    }

    @Bean
    public BuildValidationPort buildValidationPort(WorkspacePolicy policy, ProcessRunner processRunner,
            ContractGuardProperties properties) {
        ContractGuardProperties.Validation validation = properties.validation();
        ContractGuardProperties.Validation.Docker docker = validation.docker();
        if (docker != null && docker.enabled()) {
            log.info("Build validation: sandboxed ({}), network {}", docker.image(),
                    docker.networkEnabled() ? "enabled" : "disabled");
            Path mavenLocalRepo = docker.mavenLocalRepo() == null || docker.mavenLocalRepo().isBlank()
                    ? null : Path.of(docker.mavenLocalRepo());
            return new DockerBuildValidationAdapter(policy, processRunner, validation.timeout(),
                    validation.maxOutputBytes(), docker.image(), docker.memory(), docker.cpus(),
                    docker.networkEnabled(), mavenLocalRepo);
        }
        log.info("Build validation: host process (unsandboxed)");
        return new MavenBuildValidationAdapter(policy, processRunner,
                validation.timeout(), validation.maxOutputBytes());
    }

    @Bean
    public ImplementationAgent implementationAgent(LlmJsonClient client, PromptLibrary prompts,
            JsonCodec codec) {
        return new ImplementationAgent(client, prompts, codec);
    }

    @Bean
    public ExecutionService executionService(RunRepository runs, RunEventLog events,
            GitWorkspacePort git, PatchPort patches, BuildValidationPort builds,
            SourceReaderPort sourceReader, ImplementationAgent agent, ArtifactStore artifacts,
            ContractGuardProperties properties, AuditTrailService audit, Clock clock) {
        return new ExecutionService(runs, events, git, patches, builds, sourceReader, agent,
                artifacts, properties.validation().commandKey(), audit, clock);
    }

    @Bean
    public ApplicationRunner interruptedRunRecovery(RunService runService) {
        return args -> {
            RunService.InterruptedRunRecovery recovery = runService.failInterruptedRuns();
            if (recovery.resumed() > 0) {
                log.info("Resumed {} run(s) interrupted while still CREATED by the previous shutdown",
                        recovery.resumed());
            }
            if (recovery.failed() > 0) {
                log.warn("Finalised {} run(s) interrupted by the previous shutdown", recovery.failed());
            }
        };
    }
}
