package com.contractguard.testsupport;

import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory aggregate store for application-service tests. */
public class InMemoryRunRepository implements RunRepository {

    private final Map<String, AnalysisRun> runs = new LinkedHashMap<>();

    @Override
    public void save(AnalysisRun run) {
        runs.put(run.id(), run);
    }

    @Override
    public Optional<AnalysisRun> findById(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<AnalysisRun> findAll() {
        List<AnalysisRun> all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing(AnalysisRun::createdAt).reversed());
        return all;
    }

    @Override
    public List<AnalysisRun> findActiveByRepository(String repositoryId) {
        return runs.values().stream()
                .filter(run -> run.repositoryId().equals(repositoryId))
                .filter(run -> !run.state().isTerminal())
                .toList();
    }

    @Override
    public List<String> deleteFinishedBefore(Instant cutoff) {
        List<String> expired = runs.values().stream()
                .filter(run -> run.state().isTerminal())
                .filter(run -> run.updatedAt().isBefore(cutoff))
                .map(AnalysisRun::id)
                .toList();
        expired.forEach(runs::remove);
        return expired;
    }
}
