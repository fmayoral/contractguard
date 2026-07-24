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
        Remote remote,
        Audit audit,
        Concurrency concurrency,
        Cors cors,
        Notifications notifications) {

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

    public record Validation(String commandKey, Duration timeout, int maxOutputBytes, Docker docker) {

        /**
         * Sandboxed build validation (FR-030, ADR-0010): opt-in, {@code enabled=false} by
         * default. {@code mavenLocalRepo} is bind-mounted read-only in place of network
         * egress; blank/unset disables that mount.
         */
        public record Docker(boolean enabled, String image, String memory, String cpus,
                boolean networkEnabled, String mavenLocalRepo) {
        }
    }

    /** {@code credentialKey} encrypts remote-repository tokens at rest (FR-027, ADR-0007). */
    public record Remote(String credentialKey) {
    }

    /** {@code defaultPrincipal} attributes audit entries until real authentication exists (FR-025, FR-024). */
    public record Audit(String defaultPrincipal) {
    }

    /** {@code maxActiveRuns} bounds the system-wide run queue (FR-032, ADR-0009). */
    public record Concurrency(int maxActiveRuns) {
    }

    /**
     * {@code extraOrigins} adds to (never replaces) the built-in localhost/127.0.0.1
     * origins -- e.g. a LAN IP so the dashboard is reachable from another device.
     */
    public record Cors(List<String> extraOrigins) {
    }

    /**
     * {@code webhookUrl} blank disables outbound notifications entirely (FR-031, opt-in, off by
     * default like {@link Validation.Docker}). {@code dashboardBaseUrl} is optional and only adds
     * a clickable {@code url} field to the payload — the server has no public URL of its own
     * (same reasoning as the GitHub-blob-link choice in ADR-0007).
     */
    public record Notifications(String webhookUrl, String dashboardBaseUrl) {
    }
}
