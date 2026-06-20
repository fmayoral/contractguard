package com.contractguard.application.service;

import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Read access for the API layer (FR-021). Controllers call this service; they
 * never touch ports or adapters directly (architecture rule).
 */
public class RunQueryService {

    private final RunRepository runs;
    private final RunEventLog events;
    private final ArtifactStore artifacts;

    public RunQueryService(RunRepository runs, RunEventLog events, ArtifactStore artifacts) {
        this.runs = runs;
        this.events = events;
        this.artifacts = artifacts;
    }

    public AnalysisRun getRun(String runId) {
        return runs.findById(runId).orElseThrow(() -> ApprovalService.notFound(runId));
    }

    public Optional<AnalysisRun> findRun(String runId) {
        return runs.findById(runId);
    }

    public List<AnalysisRun> listRuns() {
        return runs.findAll();
    }

    public List<RunEventLog.RunEvent> eventsAfter(String runId, long afterSeq) {
        return events.eventsAfter(runId, afterSeq);
    }

    public AutoCloseable subscribe(String runId, Consumer<RunEventLog.RunEvent> listener) {
        return events.subscribe(runId, listener);
    }

    public Optional<String> readArtifact(String runId, String artifactId) {
        return artifacts.read(runId, artifactId);
    }
}
