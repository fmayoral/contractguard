package com.contractguard.config;

import com.contractguard.adapter.diff.SwaggerOpenApiDiffAdapter;
import com.contractguard.adapter.search.BoundedSourceReaderAdapter;
import com.contractguard.adapter.search.FilesystemRepositorySearchAdapter;
import com.contractguard.application.agent.ChangeExplainer;
import com.contractguard.application.agent.ImpactInvestigator;
import com.contractguard.application.agent.MigrationPlanner;
import com.contractguard.application.policy.RepositoryLock;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.ObservabilityPort;
import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.application.service.AnalysisPipeline;
import com.contractguard.application.service.AuditTrailService;
import com.contractguard.application.service.EvidenceCollector;
import com.contractguard.application.service.RemoteRepositoryService;
import com.contractguard.application.service.RunService;
import com.contractguard.application.service.SpecPreviewService;
import com.contractguard.application.service.SpecResolutionService;
import com.contractguard.application.service.SpecSourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Wires the analysis pipeline itself (§9 nodes 1-6: diff, evidence, explanation, investigation,
 * planning) and {@link RunService}, the entry point that creates a run and dispatches it onto
 * {@link #analysisExecutor()} (FR-032's bounded queue lives in {@code RunService}, not here).
 */
@Configuration
public class PipelineConfiguration {

    @Bean
    public WorkspacePolicy workspacePolicy(ContractGuardProperties properties) {
        List<Path> roots = new ArrayList<>(
                properties.workspace().roots().stream().map(Path::of).toList());
        roots.add(StorageLayout.remoteCache(properties));
        return new WorkspacePolicy(roots);
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
    public EvidenceCollector evidenceCollector(RepositorySearchPort searchPort) {
        return new EvidenceCollector(searchPort);
    }

    @Bean
    public AnalysisPipeline analysisPipeline(RunRepository runs, RunEventLog events,
            WorkspacePolicy policy, OpenApiDiffPort diffPort, EvidenceCollector evidenceCollector,
            ChangeExplainer changeExplainer, ImpactInvestigator investigator,
            MigrationPlanner planner, JsonCodec codec, AuditTrailService audit,
            ObservabilityPort observability, Clock clock) {
        return new AnalysisPipeline(runs, events, policy, diffPort, evidenceCollector,
                changeExplainer, investigator, planner, codec, audit, observability, clock);
    }

    /** Backs both the analysis pipeline dispatch below and {@code RunService}'s resume-on-restart path. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService analysisExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    @Bean
    public RepositoryLock repositoryLock() {
        return new RepositoryLock();
    }

    @Bean
    public SpecResolutionService specResolutionService(SpecSourceService specSources, ContractGuardProperties properties) {
        return new SpecResolutionService(Path.of(properties.specs().directory()),
                StorageLayout.uploadedSpecs(properties), specSources);
    }

    @Bean
    public SpecPreviewService specPreviewService(SpecResolutionService specResolution, OpenApiDiffPort diffPort) {
        return new SpecPreviewService(specResolution, diffPort);
    }

    @Bean
    public RunService runService(RunRepository runs, RunEventLog events, WorkspacePolicy policy,
            RemoteRepositoryService remoteRepositories, SpecSourceService specSources,
            SpecResolutionService specResolution, RepositoryLock repositoryLock, AnalysisPipeline pipeline,
            ContractGuardProperties properties, Executor analysisExecutor, Clock clock) {
        return new RunService(runs, events, policy, remoteRepositories, specSources, specResolution,
                repositoryLock, pipeline, Path.of(properties.specs().directory()),
                StorageLayout.uploadedSpecs(properties), analysisExecutor,
                properties.concurrency().maxActiveRuns(), clock);
    }
}
