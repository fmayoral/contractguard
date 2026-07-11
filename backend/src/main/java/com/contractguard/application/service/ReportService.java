package com.contractguard.application.service;

import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.Approval;
import com.contractguard.domain.Classification;
import com.contractguard.domain.ImpactAssessment;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.PatchArtifact;
import com.contractguard.domain.PlanItem;
import com.contractguard.domain.RunFailure;
import com.contractguard.domain.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic report generation (§9 node 11, FR-020). Reports are rendered
 * from the persisted aggregate — never from model prose — and stored as
 * run-scoped artifacts so they survive restarts and can be re-downloaded.
 */
public class ReportService {

    private final RunRepository runs;
    private final RunEventLog events;
    private final ArtifactStore artifacts;
    private final JsonCodec codec;

    public ReportService(RunRepository runs, RunEventLog events, ArtifactStore artifacts, JsonCodec codec) {
        this.runs = runs;
        this.events = events;
        this.artifacts = artifacts;
        this.codec = codec;
    }

    public String markdownReport(String runId) {
        AnalysisRun run = RunLookup.require(runs, runId);
        String markdown = renderMarkdown(run);
        artifacts.save(runId, "report.md", markdown);
        return markdown;
    }

    public String jsonReport(String runId) {
        AnalysisRun run = RunLookup.require(runs, runId);
        String json = codec.encode(reportDocument(run));
        artifacts.save(runId, "report.json", json);
        return json;
    }

    private String renderMarkdown(AnalysisRun run) {
        StringBuilder md = new StringBuilder();
        md.append("# ContractGuard Report — ").append(run.name()).append("\n\n");
        renderRunSection(md, run);
        renderChangesSection(md, run.changes());
        renderEvidenceSection(md, run.evidence());
        renderAssessmentsSection(md, run.assessments());
        renderPlanSection(md, run);
        renderApprovalSection(md, run);
        renderPatchesSection(md, run.patches());
        renderValidationSection(md, run.validations());
        renderOutcomeSection(md, run);
        renderLimitationsSection(md, run);
        renderTraceSection(md, run);
        return md.toString();
    }

    private void renderRunSection(StringBuilder md, AnalysisRun run) {
        md.append("## Run\n\n");
        md.append("| Field | Value |\n|---|---|\n");
        row(md, "Run ID", run.id());
        row(md, "Trace ID", run.traceId());
        row(md, "State", run.state().name());
        row(md, "Repository", run.repositoryId());
        row(md, "Created", String.valueOf(run.createdAt()));
        row(md, "Updated", String.valueOf(run.updatedAt()));
        row(md, "Old specification", "%s (`%s`)".formatted(nullable(run.oldSpecName()),
                nullable(run.oldSpecHash())));
        row(md, "New specification", "%s (`%s`)".formatted(nullable(run.newSpecName()),
                nullable(run.newSpecHash())));
        row(md, "Original branch", nullable(run.originalBranch()));
        row(md, "Working branch", nullable(run.workingBranch()));
        md.append('\n');
    }

    private void renderChangesSection(StringBuilder md, List<ApiChange> changes) {
        md.append("## Detected changes\n\n");
        if (changes.isEmpty()) {
            md.append("_No changes recorded._\n\n");
            return;
        }
        md.append("| Classification | Type | Where | Old | New | Reason |\n|---|---|---|---|---|---|\n");
        for (ApiChange change : changes) {
            md.append("| ").append(change.classification())
                    .append(" | ").append(change.type())
                    .append(" | ").append(where(change))
                    .append(" | ").append(code(change.oldValue()))
                    .append(" | ").append(code(change.newValue()))
                    .append(" | ").append(change.reason()).append(" |\n");
        }
        md.append('\n');
        for (ApiChange change : changes) {
            if (change.explanation() != null && !change.explanation().isBlank()) {
                md.append("- **").append(change.id()).append("**: ")
                        .append(change.explanation()).append('\n');
            }
        }
        md.append('\n');
    }

    private void renderEvidenceSection(StringBuilder md, List<ImpactEvidence> evidence) {
        md.append("## Impact evidence\n\n");
        if (evidence.isEmpty()) {
            md.append("_No repository evidence collected._\n\n");
            return;
        }
        md.append("| ID | Change | File | Line | Relationship | Snippet |\n|---|---|---|---|---|---|\n");
        for (ImpactEvidence item : evidence) {
            md.append("| ").append(item.id())
                    .append(" | ").append(item.apiChangeId())
                    .append(" | ").append(item.relativePath())
                    .append(" | ").append(item.startLine())
                    .append(" | ").append(item.relationship())
                    .append(" | ").append(code(truncate(item.snippet(), 60))).append(" |\n");
        }
        md.append('\n');
    }

    private void renderAssessmentsSection(StringBuilder md, List<ImpactAssessment> assessments) {
        md.append("## Impact assessments\n\n");
        if (assessments.isEmpty()) {
            md.append("_No assessments produced._\n\n");
            return;
        }
        for (ImpactAssessment assessment : assessments) {
            md.append("- **").append(assessment.component()).append("** (change ")
                    .append(assessment.apiChangeId()).append(", severity ")
                    .append(assessment.severity()).append(", confidence ")
                    .append(assessment.confidence()).append("): ")
                    .append(assessment.failureMode())
                    .append(" → ").append(assessment.recommendedAction())
                    .append(" _[evidence: ").append(String.join(", ", assessment.evidenceIds()))
                    .append("]_\n");
        }
        md.append('\n');
    }

    private void renderPlanSection(StringBuilder md, AnalysisRun run) {
        md.append("## Migration plan\n\n");
        run.plan().ifPresentOrElse(plan -> {
            md.append("Version ").append(plan.version()).append(", hash `")
                    .append(plan.hash()).append("`\n\n");
            for (PlanItem item : plan.items()) {
                md.append("### ").append(item.id()).append(": ").append(item.objective()).append("\n\n")
                        .append("- Action: ").append(item.proposedAction()).append('\n')
                        .append("- Files: ").append(String.join(", ", item.expectedFiles())).append('\n')
                        .append("- Tests: ").append(item.testsToUpdate().isEmpty()
                                ? "none" : String.join(", ", item.testsToUpdate())).append('\n')
                        .append("- Validation: ").append(item.validationCommand()).append('\n')
                        .append("- Risk: ").append(item.risk()).append('\n')
                        .append("- Rollback: ").append(item.rollback()).append('\n')
                        .append("- Evidence: ").append(String.join(", ", item.evidenceIds())).append("\n\n");
            }
        }, () -> md.append("_No plan generated._\n\n"));
    }

    private void renderApprovalSection(StringBuilder md, AnalysisRun run) {
        md.append("## Approval\n\n");
        run.approval().ifPresentOrElse(approval ->
                md.append("- Decision: **").append(approval.decision()).append("**\n")
                        .append("- Plan hash: `").append(approval.planHash()).append("`\n")
                        .append("- Decided at: ").append(approval.decidedAt()).append("\n\n"),
                () -> md.append("_No approval decision recorded._\n\n"));
    }

    private void renderPatchesSection(StringBuilder md, List<PatchArtifact> patches) {
        md.append("## Patches\n\n");
        if (patches.isEmpty()) {
            md.append("_No patches generated._\n\n");
            return;
        }
        for (PatchArtifact patch : patches) {
            md.append("- Attempt ").append(patch.attempt()).append(": ")
                    .append(patch.checkStatus()).append(", files: ")
                    .append(String.join(", ", patch.changedPaths()));
            if (patch.appliedAt() != null) {
                md.append(" (applied ").append(patch.appliedAt()).append(')');
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private void renderValidationSection(StringBuilder md, List<ValidationResult> validations) {
        md.append("## Validation\n\n");
        if (validations.isEmpty()) {
            md.append("_No validation executed._\n\n");
            return;
        }
        for (ValidationResult validation : validations) {
            md.append("- Attempt ").append(validation.attempt()).append(": `")
                    .append(validation.command()).append("` → exit ")
                    .append(validation.exitCode()).append(" in ")
                    .append(validation.duration().toSeconds()).append("s — ")
                    .append(validation.summary()).append('\n');
        }
        md.append('\n');
    }

    private void renderOutcomeSection(StringBuilder md, AnalysisRun run) {
        md.append("## Outcome\n\n");
        md.append("Final state: **").append(run.state()).append("**\n\n");
        run.failure().ifPresent(failure -> md.append("- Failure: ").append(failure.category())
                .append(" — ").append(failure.message()).append('\n')
                .append("- Repository mutated: ").append(failure.mutationOccurred() ? "yes" : "no")
                .append('\n')
                .append("- Remediation: ").append(failure.remediation()).append("\n\n"));
    }

    private void renderLimitationsSection(StringBuilder md, AnalysisRun run) {
        md.append("## Limitations\n\n");
        for (String limitation : limitations(run)) {
            md.append("- ").append(limitation).append('\n');
        }
        md.append('\n');
    }

    private void renderTraceSection(StringBuilder md, AnalysisRun run) {
        md.append("## Trace\n\n");
        md.append("Timeline events for trace ").append(run.traceId()).append(":\n\n");
        for (RunEventLog.RunEvent event : events.eventsAfter(run.id(), 0)) {
            md.append("- `").append(event.occurredAt()).append("` [").append(event.step())
                    .append('/').append(event.status()).append("] ").append(event.message()).append('\n');
        }
        md.append('\n');
    }

    private Map<String, Object> reportDocument(AnalysisRun run) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("runId", run.id());
        document.put("traceId", run.traceId());
        document.put("name", run.name());
        document.put("state", run.state().name());
        document.put("repositoryId", run.repositoryId());
        document.put("createdAt", String.valueOf(run.createdAt()));
        document.put("updatedAt", String.valueOf(run.updatedAt()));
        document.put("specifications", Map.of(
                "old", Map.of("name", nullable(run.oldSpecName()), "sha256", nullable(run.oldSpecHash())),
                "new", Map.of("name", nullable(run.newSpecName()), "sha256", nullable(run.newSpecHash()))));
        document.put("branches", Map.of(
                "original", nullable(run.originalBranch()),
                "working", nullable(run.workingBranch())));
        document.put("changes", run.changes().stream().map(ReportService::changeDocument).toList());
        document.put("evidence", run.evidence().stream().map(ReportService::evidenceDocument).toList());
        document.put("assessments", run.assessments().stream().map(ReportService::assessmentDocument).toList());
        document.put("plan", run.plan().map(ReportService::planDocument).orElse(null));
        document.put("approval", run.approval().map(ReportService::approvalDocument).orElse(null));
        document.put("patches", run.patches().stream().map(ReportService::patchDocument).toList());
        document.put("validations", run.validations().stream().map(ReportService::validationDocument).toList());
        document.put("failure", run.failure().map(ReportService::failureDocument).orElse(null));
        document.put("limitations", limitations(run));
        document.put("events", events.eventsAfter(run.id(), 0).stream().map(event -> Map.of(
                "seq", event.seq(), "occurredAt", String.valueOf(event.occurredAt()),
                "step", event.step(), "status", event.status(), "message", event.message())).toList());
        return document;
    }

    private static Map<String, Object> changeDocument(ApiChange change) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", change.id());
        map.put("type", change.type().name());
        map.put("classification", change.classification().name());
        map.put("method", change.method());
        map.put("path", change.path());
        map.put("schema", change.schema());
        map.put("property", change.property());
        map.put("oldValue", change.oldValue());
        map.put("newValue", change.newValue());
        map.put("reason", change.reason());
        map.put("rawEvidence", change.rawEvidence());
        map.put("explanation", change.explanation());
        return map;
    }

    private static Map<String, Object> evidenceDocument(ImpactEvidence evidence) {
        return Map.of(
                "id", evidence.id(), "apiChangeId", evidence.apiChangeId(),
                "relativePath", evidence.relativePath(), "startLine", evidence.startLine(),
                "endLine", evidence.endLine(), "snippet", evidence.snippet(),
                "searchTerm", evidence.searchTerm(), "relationship", evidence.relationship(),
                "contentHash", evidence.contentHash());
    }

    private static Map<String, Object> assessmentDocument(ImpactAssessment assessment) {
        return Map.of(
                "id", assessment.id(), "apiChangeId", assessment.apiChangeId(),
                "component", assessment.component(), "severity", assessment.severity().name(),
                "confidence", assessment.confidence().name(), "failureMode", assessment.failureMode(),
                "recommendedAction", assessment.recommendedAction(),
                "assumptions", assessment.assumptions(), "evidenceIds", assessment.evidenceIds());
    }

    private static Map<String, Object> planDocument(MigrationPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", plan.id());
        map.put("version", plan.version());
        map.put("hash", plan.hash());
        map.put("createdAt", String.valueOf(plan.createdAt()));
        map.put("items", plan.items().stream().map(item -> Map.of(
                "id", item.id(), "objective", item.objective(),
                "expectedFiles", item.expectedFiles(), "proposedAction", item.proposedAction(),
                "testsToUpdate", item.testsToUpdate(), "validationCommand", item.validationCommand(),
                "risk", item.risk(), "rollback", item.rollback(),
                "evidenceIds", item.evidenceIds())).toList());
        return map;
    }

    private static Map<String, Object> approvalDocument(Approval approval) {
        return Map.of(
                "decision", approval.decision().name(), "planHash", approval.planHash(),
                "decidedAt", String.valueOf(approval.decidedAt()));
    }

    private static Map<String, Object> patchDocument(PatchArtifact patch) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", patch.id());
        map.put("attempt", patch.attempt());
        map.put("checkStatus", patch.checkStatus().name());
        map.put("changedPaths", patch.changedPaths());
        map.put("appliedAt", patch.appliedAt() == null ? null : String.valueOf(patch.appliedAt()));
        return map;
    }

    private static Map<String, Object> validationDocument(ValidationResult validation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attempt", validation.attempt());
        map.put("command", validation.command());
        map.put("exitCode", validation.exitCode());
        map.put("durationMillis", validation.duration().toMillis());
        map.put("summary", validation.summary());
        map.put("successful", validation.successful());
        map.put("outputArtifactId", validation.outputArtifactId());
        return map;
    }

    private static Map<String, Object> failureDocument(RunFailure failure) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("category", failure.category().name());
        map.put("message", failure.message());
        map.put("mutationOccurred", failure.mutationOccurred());
        map.put("artifactId", failure.artifactId());
        map.put("remediation", failure.remediation());
        return map;
    }

    /** Honest, run-specific statement of what this analysis did not cover. */
    private List<String> limitations(AnalysisRun run) {
        List<String> limitations = new ArrayList<>();
        long unknown = run.changes().stream()
                .filter(change -> change.classification() == Classification.UNKNOWN).count();
        if (unknown > 0) {
            limitations.add(unknown + " change(s) fall outside the analysed categories and need manual review.");
        }
        long potentially = run.changes().stream()
                .filter(change -> change.classification() == Classification.POTENTIALLY_BREAKING).count();
        if (potentially > 0) {
            limitations.add(potentially + " potentially-breaking change(s) were not remediated automatically.");
        }
        limitations.add("Evidence is text-search based; dynamically constructed references may be missed.");
        limitations.add("Remediation covers endpoint renames, property renames and enum removals; "
                + "other breaking categories require manual migration.");
        limitations.add("At most one automated repair attempt is made after a failed validation.");
        limitations.add("The working branch is left uncommitted and unmerged for human review.");
        return limitations;
    }

    private static void row(StringBuilder md, String field, String value) {
        md.append("| ").append(field).append(" | ").append(value).append(" |\n");
    }

    private static String where(ApiChange change) {
        if (change.path() != null) {
            return (change.method() == null ? "" : change.method() + " ") + change.path();
        }
        return change.schema() + (change.property() == null ? "" : "." + change.property());
    }

    private static String code(String value) {
        return value == null || value.isBlank() ? "—" : "`" + value + "`";
    }

    private static String nullable(String value) {
        return value == null ? "—" : value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
