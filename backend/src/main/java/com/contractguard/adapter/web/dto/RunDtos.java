package com.contractguard.adapter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Versionable API DTOs (§13). Deliberately decoupled from domain classes:
 * the wire shape can evolve without touching the domain, and domain objects
 * are never serialised directly.
 */
public final class RunDtos {

    private RunDtos() {
    }

    public record CreateRunRequest(
            String name,
            @jakarta.validation.constraints.NotBlank String repositoryId,
            @jakarta.validation.constraints.NotBlank String oldSpec,
            @jakarta.validation.constraints.NotBlank String newSpec) {
    }

    public record ApprovalRequest(
            @jakarta.validation.constraints.NotBlank String decision,
            @jakarta.validation.constraints.NotBlank String planHash) {
    }

    public record RunSummary(String id, String name, String state, String repositoryId,
            Instant createdAt, Instant updatedAt, String workingBranch, String failureCategory) {
    }

    public record RunDetail(String id, String name, String state, String repositoryId, String traceId,
            Instant createdAt, Instant updatedAt, String oldSpecName, String newSpecName,
            String oldSpecHash, String newSpecHash, String originalBranch, String workingBranch,
            Failure failure, ApprovalInfo approval, List<Change> changes, List<Evidence> evidence,
            List<Assessment> assessments, Plan plan, List<Patch> patches, List<Validation> validations) {
    }

    public record Failure(String category, String message, boolean mutationOccurred,
            String artifactId, String remediation) {
    }

    public record ApprovalInfo(String decision, String planHash, Instant decidedAt) {
    }

    public record Change(String id, String type, String classification, String method, String path,
            String schema, String property, String oldValue, String newValue, String reason,
            String explanation) {
    }

    public record Evidence(String id, String apiChangeId, String relativePath, int startLine,
            int endLine, String snippet, String searchTerm, String relationship) {
    }

    public record Assessment(String id, String apiChangeId, String component, String severity,
            String confidence, String failureMode, String recommendedAction,
            List<String> assumptions, List<String> evidenceIds) {
    }

    public record Plan(String id, int version, String hash, Instant createdAt, List<PlanItem> items,
            List<String> approvedFiles) {
    }

    public record PlanItem(String id, String objective, List<String> expectedFiles,
            String proposedAction, List<String> testsToUpdate, String validationCommand,
            String risk, String rollback, List<String> evidenceIds) {
    }

    public record Patch(String id, int attempt, String checkStatus, List<String> changedPaths,
            Instant appliedAt, String unifiedDiff) {
    }

    public record Validation(int attempt, String command, int exitCode, Instant startedAt,
            long durationMillis, String summary, boolean successful, String outputArtifactId) {
    }

    public record Event(String runId, long seq, Instant occurredAt, String step, String status,
            String message, String metadata) {
    }

    public record SetupOptions(List<String> repositories, List<String> specifications) {
    }
}
