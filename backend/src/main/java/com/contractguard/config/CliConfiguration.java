package com.contractguard.config;

import com.contractguard.adapter.cli.CliRunner;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.service.AnalysisPipeline;
import com.contractguard.application.service.ReportService;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.RunService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Wiring for the headless CI gate (FR-026), active only on the {@code cli}
 * profile. The CLI gets its own {@link RunService} with a same-thread
 * executor so the analysis completes before the process decides its exit
 * code; the web wiring and its thread pool stay untouched.
 */
@Configuration
@Profile("cli")
public class CliConfiguration {

    @Bean
    public CliRunner cliRunner(RunRepository runs, RunEventLog events, WorkspacePolicy policy,
            AnalysisPipeline pipeline, ContractGuardProperties properties,
            RunQueryService queries, ReportService reports, Clock clock) {
        RunService synchronousRunService = new RunService(runs, events, policy, pipeline,
                Path.of(properties.specs().directory()), Runnable::run, clock);
        return new CliRunner(synchronousRunService, queries, reports, System.out);
    }

    @Bean
    public CliBootstrap cliBootstrap(CliRunner runner, Environment environment) {
        return new CliBootstrap(runner, environment);
    }
}
