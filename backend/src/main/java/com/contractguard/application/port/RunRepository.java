package com.contractguard.application.port;

import com.contractguard.domain.AnalysisRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence of the run aggregate; completed runs survive restart (§15). */
public interface RunRepository {

    void save(AnalysisRun run);

    Optional<AnalysisRun> findById(String runId);

    /** All runs ordered by creation time, newest first. */
    List<AnalysisRun> findAll();

    /** Runs in a non-terminal state operating on the given repository. */
    List<AnalysisRun> findActiveByRepository(String repositoryId);

    /**
     * Deletes terminal-state runs last updated before the cutoff (FR-023).
     * Active runs are never deleted regardless of age.
     *
     * @return the IDs of the deleted runs, for cleanup of events and artifacts
     */
    List<String> deleteFinishedBefore(Instant cutoff);
}
