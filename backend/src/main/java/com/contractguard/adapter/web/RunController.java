package com.contractguard.adapter.web;

import com.contractguard.adapter.web.dto.DtoMapper;
import com.contractguard.adapter.web.dto.RunDtos;
import com.contractguard.application.service.ApprovalService;
import com.contractguard.application.service.ExecutionService;
import com.contractguard.application.service.PublishService;
import com.contractguard.application.service.RemoteRepositoryService;
import com.contractguard.application.service.ReportService;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.RunService;
import com.contractguard.application.service.SpecOption;
import com.contractguard.application.service.SpecSourceService;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** REST surface (§13). Thin by design: every decision lives in application services. */
@RestController
@RequestMapping("/api")
public class RunController {

    private final RunService runService;
    private final RunQueryService queries;
    private final ApprovalService approvals;
    private final ExecutionService executions;
    private final PublishService publishing;
    private final RemoteRepositoryService remoteRepositories;
    private final SpecSourceService specSources;
    private final ReportService reports;
    private final ExecutorService executor;

    // Explicit one-dependency-per-parameter constructor, consistent with this project's
    // hexagonal-architecture style throughout -- no bundling into a config/context object.
    @SuppressWarnings("java:S107")
    public RunController(RunService runService, RunQueryService queries, ApprovalService approvals,
            ExecutionService executions, PublishService publishing,
            RemoteRepositoryService remoteRepositories, SpecSourceService specSources,
            ReportService reports, ExecutorService executor) {
        this.runService = runService;
        this.queries = queries;
        this.approvals = approvals;
        this.executions = executions;
        this.publishing = publishing;
        this.remoteRepositories = remoteRepositories;
        this.specSources = specSources;
        this.reports = reports;
        this.executor = executor;
    }

    @GetMapping("/setup")
    public RunDtos.SetupOptions setup() {
        return new RunDtos.SetupOptions(runService.listRepositories(),
                runService.listSpecOptions().stream().map(DtoMapper::toSpecOption).toList(),
                remoteRepositories.listRegistered().stream().map(DtoMapper::toRemoteRepositorySummary).toList(),
                specSources.listRegistered().stream().map(DtoMapper::toSpecSourceSummary).toList());
    }

    @PostMapping("/runs")
    public ResponseEntity<RunDtos.RunSummary> createRun(@Valid @RequestBody RunDtos.CreateRunRequest request) {
        AnalysisRun run = runService.createRun(request.name(), request.repositoryId(),
                request.oldSpec(), request.newSpec());
        return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(run));
    }

    @PostMapping(value = "/specs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RunDtos.SpecOption> uploadSpecification(@RequestParam("file") MultipartFile file) {
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read uploaded specification", e);
        }
        SpecOption uploaded = runService.uploadSpecification(file.getOriginalFilename(), content);
        return ResponseEntity.status(HttpStatus.CREATED).body(DtoMapper.toSpecOption(uploaded));
    }

    @DeleteMapping("/specs/{fileName}")
    public ResponseEntity<Void> deleteSpecification(@PathVariable String fileName) {
        runService.deleteUploadedSpecification(fileName);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/runs")
    public List<RunDtos.RunSummary> listRuns() {
        return queries.listRuns().stream().map(this::toSummary).toList();
    }

    @GetMapping("/runs/{runId}")
    public RunDtos.RunDetail getRun(@PathVariable String runId) {
        return toDetail(queries.getRun(runId));
    }

    @GetMapping("/runs/{runId}/changes")
    public List<RunDtos.Change> getChanges(@PathVariable String runId) {
        return DtoMapper.toChanges(queries.getRun(runId));
    }

    @GetMapping("/runs/{runId}/impacts")
    public RunDtos.RunDetail getImpacts(@PathVariable String runId) {
        // Impacts = assessments plus their evidence; served via the detail shape.
        return toDetail(queries.getRun(runId));
    }

    @GetMapping("/runs/{runId}/plan")
    public RunDtos.Plan getPlan(@PathVariable String runId) {
        RunDtos.Plan plan = DtoMapper.toPlan(queries.getRun(runId));
        if (plan == null) {
            throw ContractGuardException.of(FailureCategory.NOT_FOUND,
                    "run %s has no migration plan yet".formatted(runId),
                    "Wait for the analysis to reach AWAITING_APPROVAL.");
        }
        return plan;
    }

    @PostMapping("/runs/{runId}/approval")
    public RunDtos.RunDetail decide(@PathVariable String runId,
            @Valid @RequestBody RunDtos.ApprovalRequest request) {
        Approval.Decision decision = parseDecision(request.decision());
        return toDetail(approvals.decide(runId, decision, request.planHash()));
    }

    @PostMapping("/runs/{runId}/execute")
    public ResponseEntity<RunDtos.RunSummary> execute(@PathVariable String runId) {
        AnalysisRun run = executions.beginExecution(runId);
        executor.execute(() -> executions.execute(runId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toSummary(run));
    }

    @PostMapping("/runs/{runId}/publish")
    public ResponseEntity<RunDtos.RunSummary> publish(@PathVariable String runId) {
        AnalysisRun run = publishing.beginPublish(runId);
        executor.execute(() -> publishing.publish(runId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toSummary(run));
    }

    @GetMapping(value = "/runs/{runId}/artifacts/report.md", produces = "text/markdown;charset=UTF-8")
    public String markdownReport(@PathVariable String runId) {
        return reports.markdownReport(runId);
    }

    @GetMapping(value = "/runs/{runId}/artifacts/report.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String jsonReport(@PathVariable String runId) {
        return reports.jsonReport(runId);
    }

    @GetMapping(value = "/runs/{runId}/artifacts/{artifactId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String artifact(@PathVariable String runId, @PathVariable String artifactId) {
        return queries.readArtifact(runId, artifactId).orElseThrow(
                () -> ContractGuardException.of(FailureCategory.NOT_FOUND,
                        "artifact %s not found for run %s".formatted(artifactId, runId),
                        "List the run's validations and patches for valid artifact IDs."));
    }

    private RunDtos.RunSummary toSummary(AnalysisRun run) {
        return DtoMapper.toSummary(run, remoteRepositories.isRemote(run.repositoryId()));
    }

    private RunDtos.RunDetail toDetail(AnalysisRun run) {
        return DtoMapper.toDetail(run, remoteRepositories.isRemote(run.repositoryId()));
    }

    private static Approval.Decision parseDecision(String raw) {
        try {
            return Approval.Decision.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                    "invalid decision '%s'; expected APPROVED or REJECTED".formatted(raw),
                    "Send decision APPROVED or REJECTED.");
        }
    }
}
