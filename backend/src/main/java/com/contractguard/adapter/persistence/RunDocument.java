package com.contractguard.adapter.persistence;

import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.Approval;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.Confidence;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.ImpactAssessment;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.PatchArtifact;
import com.contractguard.domain.PlanItem;
import com.contractguard.domain.RunFailure;
import com.contractguard.domain.RunState;
import com.contractguard.domain.Severity;
import com.contractguard.domain.ValidationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Versioned persistence representation of the run aggregate (ADR-0004).
 * Deliberately separate from the domain classes so the stored shape can
 * evolve independently; {@code CURRENT_VERSION} guards future migrations.
 */
public record RunDocument(
        String id, String name, String repositoryId, String traceId,
        Instant createdAt, Instant updatedAt, String state,
        String oldSpecFile, String newSpecFile,
        String oldSpecName, String newSpecName, String oldSpecHash, String newSpecHash,
        String originalBranch, String workingBranch, Failure failure, String pullRequestUrl,
        List<Change> changes, List<Evidence> evidence, List<Assessment> assessments,
        Plan plan, ApprovalDoc approval, List<Patch> patches, List<Validation> validations) {

    /**
     * v2 added {@code pullRequestUrl} (FR-027); v3 adds {@code oldSpecFile}/{@code newSpecFile}
     * (FR-032, ADR-0009) so a run interrupted while still CREATED can be safely resumed. Older
     * payloads deserialise the new fields as null.
     */
    public static final int CURRENT_VERSION = 3;

    public record Failure(String category, String message, boolean mutationOccurred,
            String artifactId, String remediation) {
    }

    public record Change(String id, String type, String classification, String method, String path,
            String schema, String property, String oldValue, String newValue, String reason,
            String rawEvidence, String explanation) {
    }

    public record Evidence(String id, String apiChangeId, String relativePath, int startLine, int endLine,
            String snippet, String searchTerm, String relationship, String contentHash) {
    }

    public record Assessment(String id, String apiChangeId, String component, String severity,
            String confidence, String failureMode, String recommendedAction,
            List<String> assumptions, List<String> evidenceIds) {
    }

    public record Plan(String id, int version, String hash, List<Item> items, Instant createdAt) {

        public record Item(String id, String objective, List<String> expectedFiles, String proposedAction,
                List<String> testsToUpdate, String validationCommand, String risk, String rollback,
                List<String> evidenceIds) {
        }
    }

    public record ApprovalDoc(String runId, String planHash, String decision, Instant decidedAt) {
    }

    public record Patch(String id, String runId, int attempt, String unifiedDiff,
            List<String> changedPaths, String checkStatus, Instant appliedAt) {
    }

    public record Validation(int attempt, String command, int exitCode, Instant startedAt,
            long durationMillis, String summary, String outputArtifactId, boolean successful) {
    }

    public static RunDocument fromDomain(AnalysisRun run) {
        return new RunDocument(
                run.id(), run.name(), run.repositoryId(), run.traceId(),
                run.createdAt(), run.updatedAt(), run.state().name(),
                run.oldSpecFile(), run.newSpecFile(),
                run.oldSpecName(), run.newSpecName(), run.oldSpecHash(), run.newSpecHash(),
                run.originalBranch(), run.workingBranch(),
                run.failure().map(f -> new Failure(f.category().name(), f.message(),
                        f.mutationOccurred(), f.artifactId(), f.remediation())).orElse(null),
                run.pullRequestUrl().orElse(null),
                run.changes().stream().map(c -> new Change(c.id(), c.type().name(),
                        c.classification().name(), c.method(), c.path(), c.schema(), c.property(),
                        c.oldValue(), c.newValue(), c.reason(), c.rawEvidence(), c.explanation())).toList(),
                run.evidence().stream().map(e -> new Evidence(e.id(), e.apiChangeId(), e.relativePath(),
                        e.startLine(), e.endLine(), e.snippet(), e.searchTerm(), e.relationship(),
                        e.contentHash())).toList(),
                run.assessments().stream().map(a -> new Assessment(a.id(), a.apiChangeId(), a.component(),
                        a.severity().name(), a.confidence().name(), a.failureMode(), a.recommendedAction(),
                        a.assumptions(), a.evidenceIds())).toList(),
                run.plan().map(p -> new Plan(p.id(), p.version(), p.hash(),
                        p.items().stream().map(i -> new Plan.Item(i.id(), i.objective(), i.expectedFiles(),
                                i.proposedAction(), i.testsToUpdate(), i.validationCommand(), i.risk(),
                                i.rollback(), i.evidenceIds())).toList(),
                        p.createdAt())).orElse(null),
                run.approval().map(a -> new ApprovalDoc(a.runId(), a.planHash(),
                        a.decision().name(), a.decidedAt())).orElse(null),
                run.patches().stream().map(p -> new Patch(p.id(), p.runId(), p.attempt(), p.unifiedDiff(),
                        p.changedPaths(), p.checkStatus().name(), p.appliedAt())).toList(),
                run.validations().stream().map(v -> new Validation(v.attempt(), v.command(), v.exitCode(),
                        v.startedAt(), v.duration().toMillis(), v.summary(), v.outputArtifactId(),
                        v.successful())).toList());
    }

    public AnalysisRun toDomain() {
        return AnalysisRun.rehydrate(
                id, name, repositoryId, traceId, createdAt, updatedAt, RunState.valueOf(state),
                oldSpecFile, newSpecFile,
                oldSpecName, newSpecName, oldSpecHash, newSpecHash, originalBranch, workingBranch,
                failure == null ? null : new RunFailure(FailureCategory.valueOf(failure.category()),
                        failure.message(), failure.mutationOccurred(), failure.artifactId(),
                        failure.remediation()),
                pullRequestUrl,
                changes.stream().map(c -> new ApiChange(c.id(), ChangeType.valueOf(c.type()),
                        Classification.valueOf(c.classification()), c.method(), c.path(), c.schema(),
                        c.property(), c.oldValue(), c.newValue(), c.reason(), c.rawEvidence(),
                        c.explanation())).toList(),
                evidence.stream().map(e -> new ImpactEvidence(e.id(), e.apiChangeId(), e.relativePath(),
                        e.startLine(), e.endLine(), e.snippet(), e.searchTerm(), e.relationship(),
                        e.contentHash())).toList(),
                assessments.stream().map(a -> new ImpactAssessment(a.id(), a.apiChangeId(), a.component(),
                        Severity.valueOf(a.severity()), Confidence.valueOf(a.confidence()), a.failureMode(),
                        a.recommendedAction(), a.assumptions(), a.evidenceIds())).toList(),
                plan == null ? null : new MigrationPlan(plan.id(), plan.version(), plan.hash(),
                        plan.items().stream().map(i -> new PlanItem(i.id(), i.objective(), i.expectedFiles(),
                                i.proposedAction(), i.testsToUpdate(), i.validationCommand(), i.risk(),
                                i.rollback(), i.evidenceIds())).toList(),
                        plan.createdAt()),
                approval == null ? null : new Approval(approval.runId(), approval.planHash(),
                        Approval.Decision.valueOf(approval.decision()), approval.decidedAt()),
                patches.stream().map(p -> new PatchArtifact(p.id(), p.runId(), p.attempt(), p.unifiedDiff(),
                        p.changedPaths(), PatchArtifact.CheckStatus.valueOf(p.checkStatus()),
                        p.appliedAt())).toList(),
                validations.stream().map(v -> new ValidationResult(v.attempt(), v.command(), v.exitCode(),
                        v.startedAt(), Duration.ofMillis(v.durationMillis()), v.summary(),
                        v.outputArtifactId(), v.successful())).toList());
    }
}
