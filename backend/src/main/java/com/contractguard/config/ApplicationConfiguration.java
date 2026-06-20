package com.contractguard.config;

import com.contractguard.adapter.artifacts.FilesystemArtifactStore;
import com.contractguard.adapter.diff.SwaggerOpenApiDiffAdapter;
import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.adapter.llm.LlmSettings;
import com.contractguard.adapter.llm.OpenAiCompatibleLlmGateway;
import com.contractguard.adapter.llm.ScriptedLlmGateway;
import com.contractguard.adapter.persistence.JdbcRunEventLog;
import com.contractguard.adapter.persistence.JdbcRunRepository;
import com.contractguard.adapter.search.BoundedSourceReaderAdapter;
import com.contractguard.adapter.search.FilesystemRepositorySearchAdapter;
import com.contractguard.application.agent.ChangeExplainer;
import com.contractguard.application.agent.ImpactInvestigator;
import com.contractguard.application.agent.MigrationPlanner;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.LlmGateway;
import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.application.service.AnalysisPipeline;
import com.contractguard.application.service.ApprovalService;
import com.contractguard.application.service.EvidenceCollector;
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
        return new WorkspacePolicy(properties.workspace().roots().stream().map(Path::of).toList());
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
    public AnalysisPipeline analysisPipeline(RunRepository runs, RunEventLog events,
            WorkspacePolicy policy, OpenApiDiffPort diffPort, EvidenceCollector evidenceCollector,
            ChangeExplainer changeExplainer, ImpactInvestigator investigator,
            MigrationPlanner planner, JsonCodec codec, Clock clock) {
        return new AnalysisPipeline(runs, events, policy, diffPort, evidenceCollector,
                changeExplainer, investigator, planner, codec, clock);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService analysisExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    @Bean
    public RunService runService(RunRepository runs, RunEventLog events, WorkspacePolicy policy,
            AnalysisPipeline pipeline, ContractGuardProperties properties,
            ExecutorService analysisExecutor, Clock clock) {
        return new RunService(runs, events, policy, pipeline,
                Path.of(properties.specs().directory()), analysisExecutor, clock);
    }

    @Bean
    public ApprovalService approvalService(RunRepository runs, RunEventLog events, Clock clock) {
        return new ApprovalService(runs, events, clock);
    }

    @Bean
    public RunQueryService runQueryService(RunRepository runs, RunEventLog events, ArtifactStore artifacts) {
        return new RunQueryService(runs, events, artifacts);
    }

    @Bean
    public ApplicationRunner interruptedRunRecovery(RunService runService) {
        return args -> {
            int failed = runService.failInterruptedRuns();
            if (failed > 0) {
                log.warn("Finalised {} run(s) interrupted by the previous shutdown", failed);
            }
        };
    }
}
