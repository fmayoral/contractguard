package com.contractguard.application.service;

import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Deletes finished runs older than the configured retention window, together
 * with their events and artifacts (FR-023). A non-positive window disables
 * retention entirely; active runs are never touched regardless of age.
 */
public class RetentionService {

    private final RunRepository runs;
    private final RunEventLog events;
    private final ArtifactStore artifacts;
    private final int retentionDays;
    private final Clock clock;

    public RetentionService(RunRepository runs, RunEventLog events, ArtifactStore artifacts,
            int retentionDays, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.artifacts = artifacts;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    /** @return IDs of the runs that were purged */
    public List<String> purgeExpired() {
        if (retentionDays <= 0) {
            return List.of();
        }
        List<String> deleted = runs.deleteFinishedBefore(
                clock.instant().minus(Duration.ofDays(retentionDays)));
        for (String runId : deleted) {
            events.deleteForRun(runId);
            artifacts.deleteForRun(runId);
        }
        return deleted;
    }
}
