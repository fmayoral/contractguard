package com.contractguard.adapter.web.dto;

import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.SpecOption;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.RemoteRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maps domain objects to wire DTOs; the only place that knows both shapes. */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static RunDtos.SpecOption toSpecOption(SpecOption option) {
        return new RunDtos.SpecOption(option.id(), option.label(),
                option.origin().name().toLowerCase(Locale.ROOT), option.sourceId());
    }

    public static RunDtos.SpecSourceSummary toSpecSourceSummary(RemoteRepository source) {
        return new RunDtos.SpecSourceSummary(source.repositoryId(), source.owner(), source.name(),
                source.defaultBranch(), source.registeredAt());
    }

    public static RunDtos.RemoteRepositorySummary toRemoteRepositorySummary(RemoteRepository repository) {
        return new RunDtos.RemoteRepositorySummary(repository.repositoryId(), repository.owner(),
                repository.name(), repository.defaultBranch(), repository.registeredAt());
    }

    public static RunDtos.RunSummary toSummary(AnalysisRun run, boolean remoteRepository) {
        return new RunDtos.RunSummary(run.id(), run.name(), run.state().name(), run.repositoryId(),
                run.createdAt(), run.updatedAt(), run.workingBranch(),
                run.failure().map(f -> f.category().name()).orElse(null),
                run.pullRequestUrl().orElse(null), remoteRepository);
    }

    public static RunDtos.RunDetail toDetail(AnalysisRun run, boolean remoteRepository) {
        return new RunDtos.RunDetail(run.id(), run.name(), run.state().name(), run.repositoryId(),
                run.traceId(), run.createdAt(), run.updatedAt(),
                run.oldSpecName(), run.newSpecName(), run.oldSpecHash(), run.newSpecHash(),
                run.originalBranch(), run.workingBranch(),
                run.failure().map(f -> new RunDtos.Failure(f.category().name(), f.message(),
                        f.mutationOccurred(), f.artifactId(), f.remediation())).orElse(null),
                run.pullRequestUrl().orElse(null), remoteRepository,
                run.approval().map(a -> new RunDtos.ApprovalInfo(a.decision().name(), a.planHash(),
                        a.decidedAt())).orElse(null),
                toChanges(run), toEvidence(run), toAssessments(run),
                toPlan(run), toPatches(run), toValidations(run));
    }

    public static List<RunDtos.Change> toChanges(AnalysisRun run) {
        return run.changes().stream().map(c -> new RunDtos.Change(c.id(), c.type().name(),
                c.classification().name(), c.method(), c.path(), c.schema(), c.property(),
                c.oldValue(), c.newValue(), c.reason(), c.explanation())).toList();
    }

    public static List<RunDtos.Evidence> toEvidence(AnalysisRun run) {
        return run.evidence().stream().map(e -> new RunDtos.Evidence(e.id(), e.apiChangeId(),
                e.relativePath(), e.startLine(), e.endLine(), e.snippet(), e.searchTerm(),
                e.relationship())).toList();
    }

    public static List<RunDtos.Assessment> toAssessments(AnalysisRun run) {
        return run.assessments().stream().map(a -> new RunDtos.Assessment(a.id(), a.apiChangeId(),
                a.component(), a.severity().name(), a.confidence().name(), a.failureMode(),
                a.recommendedAction(), a.assumptions(), a.evidenceIds())).toList();
    }

    public static RunDtos.Plan toPlan(AnalysisRun run) {
        return run.plan().map(plan -> new RunDtos.Plan(plan.id(), plan.version(), plan.hash(),
                plan.createdAt(),
                plan.items().stream().map(item -> new RunDtos.PlanItem(item.id(), item.objective(),
                        item.expectedFiles(), item.proposedAction(), item.testsToUpdate(),
                        item.validationCommand(), item.risk(), item.rollback(),
                        item.evidenceIds())).toList(),
                new ArrayList<>(plan.approvedFiles()))).orElse(null);
    }

    public static List<RunDtos.Patch> toPatches(AnalysisRun run) {
        return run.patches().stream().map(p -> new RunDtos.Patch(p.id(), p.attempt(),
                p.checkStatus().name(), p.changedPaths(), p.appliedAt(), p.unifiedDiff())).toList();
    }

    public static List<RunDtos.Validation> toValidations(AnalysisRun run) {
        return run.validations().stream().map(v -> new RunDtos.Validation(v.attempt(), v.command(),
                v.exitCode(), v.startedAt(), v.duration().toMillis(), v.summary(), v.successful(),
                v.outputArtifactId())).toList();
    }

    public static RunDtos.Event toEvent(RunQueryService.EventView event) {
        return new RunDtos.Event(event.runId(), event.seq(), event.occurredAt(), event.step(),
                event.status(), event.message(), event.metadataJson());
    }
}
