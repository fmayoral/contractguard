package com.contractguard.config;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.ObservabilityPort;
import com.contractguard.application.port.PullRequestPort;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.service.ApprovalService;
import com.contractguard.application.service.AuditTrailService;
import com.contractguard.application.service.PublishService;
import com.contractguard.application.service.ReportService;
import com.contractguard.application.service.RetentionService;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.RunService;
import com.contractguard.application.service.RunStatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Composition root: adapters are chosen and wired to ports here and in the sibling
 * {@code *Configuration} classes in this package (each one topic -- LLM, persistence, remote
 * repositories, execution, observability, the analysis pipeline). This class itself holds what's
 * left: cross-cutting beans ({@link Clock}, {@link JsonCodec}), the services that sit downstream
 * of run completion (approval, publishing, retention, querying, reporting), and startup recovery.
 */
@Configuration
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
    public PublishService publishService(RunRepository runs, RunEventLog events, GitWorkspacePort git,
            RemoteGitPort remoteGit, PullRequestPort pullRequests, RemoteRepositoryRegistry remoteRepositories,
            AuditTrailService audit, ObservabilityPort observability, Clock clock) {
        return new PublishService(runs, events, git, remoteGit, pullRequests, remoteRepositories, audit,
                observability, clock);
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
    public RunStatisticsService runStatisticsService(RunRepository runs, Clock clock) {
        return new RunStatisticsService(runs, clock);
    }

    @Bean
    public ReportService reportService(RunRepository runs, RunEventLog events,
            ArtifactStore artifacts, JsonCodec codec) {
        return new ReportService(runs, events, artifacts, codec);
    }

    /** Recovers runs interrupted by the previous shutdown (FR-032, ADR-0009) before traffic is served. */
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
