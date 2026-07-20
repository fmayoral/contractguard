package com.contractguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Typed view of the {@code contractguard.*} configuration (§16, §17). */
@ConfigurationProperties(prefix = "contractguard")
public record ContractGuardProperties(
        Workspace workspace,
        Storage storage,
        Specs specs,
        Llm llm,
        Validation validation,
        Remote remote) {

    public record Workspace(List<String> roots) {
    }

    /** {@code retentionDays <= 0} keeps finished runs forever (FR-023). */
    public record Storage(String directory, int retentionDays) {
    }

    public record Specs(String directory) {
    }

    public record Llm(String provider, String baseUrl, String apiKey, String model,
            Duration timeout, int maxTokens, double temperature, int maxWorkflowSteps) {
    }

    public record Validation(String commandKey, Duration timeout, int maxOutputBytes) {
    }

    /** {@code credentialKey} encrypts remote-repository tokens at rest (FR-027, ADR-0007). */
    public record Remote(String credentialKey) {
    }
}
