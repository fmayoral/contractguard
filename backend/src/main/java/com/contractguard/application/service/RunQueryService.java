package com.contractguard.application.service;

import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Read access for the API layer (FR-021). Controllers call this service; they
 * never touch ports or adapters directly (architecture rule), so events are
 * exposed through the service-owned {@link EventView} rather than port types.
 */
public class RunQueryService {

    public record EventView(String runId, long seq, Instant occurredAt, String step, String status,
            String message, String metadataJson) {

        static EventView of(RunEventLog.RunEvent event) {
            return new EventView(event.runId(), event.seq(), event.occurredAt(), event.step(),
                    event.status(), event.message(), event.metadataJson());
        }
    }

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

    public List<EventView> eventsAfter(String runId, long afterSeq) {
        return events.eventsAfter(runId, afterSeq).stream().map(EventView::of).toList();
    }

    public AutoCloseable subscribe(String runId, Consumer<EventView> listener) {
        return events.subscribe(runId, event -> listener.accept(EventView.of(event)));
    }

    public Optional<String> readArtifact(String runId, String artifactId) {
        return artifacts.read(runId, artifactId);
    }
}
