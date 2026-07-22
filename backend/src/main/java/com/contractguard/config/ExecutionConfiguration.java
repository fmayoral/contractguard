package com.contractguard.config;

import com.contractguard.adapter.git.GitCliAdapter;
import com.contractguard.adapter.process.DockerBuildValidationAdapter;
import com.contractguard.adapter.process.MavenBuildValidationAdapter;
import com.contractguard.adapter.process.ProcessRunner;
import com.contractguard.application.agent.ImplementationAgent;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.BuildValidationPort;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.ObservabilityPort;
import com.contractguard.application.port.PatchPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.application.service.AuditTrailService;
import com.contractguard.application.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Wires an approved run's execution (§9 nodes 7-10: branch, patch, validate, repair). Build
 * validation is either the host process ({@link MavenBuildValidationAdapter}) or a sandboxed
 * container ({@link DockerBuildValidationAdapter}, opt-in, FR-030/ADR-0010) depending on
 * {@code contractguard.validation.docker.enabled}.
 */
@Configuration
public class ExecutionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExecutionConfiguration.class);

    @Bean
    public ProcessRunner processRunner() {
        return new ProcessRunner();
    }

    /** Registered as {@link GitWorkspacePort} and {@link PatchPort} both -- {@link GitCliAdapter} implements both. */
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
    public ExecutionService executionService(RunRepository runs, RunEventLog events,
            GitWorkspacePort git, PatchPort patches, BuildValidationPort builds,
            SourceReaderPort sourceReader, ImplementationAgent agent, ArtifactStore artifacts,
            ContractGuardProperties properties, AuditTrailService audit,
            ObservabilityPort observability, Clock clock) {
        return new ExecutionService(runs, events, git, patches, builds, sourceReader, agent,
                artifacts, properties.validation().commandKey(), audit, observability, clock);
    }
}
