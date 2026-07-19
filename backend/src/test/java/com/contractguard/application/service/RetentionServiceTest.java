package com.contractguard.application.service;

import com.contractguard.application.port.ArtifactStore;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Fixtures;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionServiceTest {

    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final InMemoryRunEventLog events = new InMemoryRunEventLog();
    private final Set<String> deletedArtifactRuns = new HashSet<>();
    private final Map<String, String> artifacts = new HashMap<>();

    private final ArtifactStore artifactStore = new ArtifactStore() {
        @Override
        public String save(String runId, String name, String content) {
            artifacts.put(runId + "/" + name, content);
            return name;
        }

        @Override
        public Optional<String> read(String runId, String artifactId) {
            return Optional.ofNullable(artifacts.get(runId + "/" + artifactId));
        }

        @Override
        public void deleteForRun(String runId) {
            deletedArtifactRuns.add(runId);
            artifacts.keySet().removeIf(key -> key.startsWith(runId + "/"));
        }
    };

    @Test
    void nonPositiveRetentionDisablesPurging() {
        AnalysisRun ancient = new AnalysisRun("run-1", "r", "repo", "t", Fixtures.T0);
        ancient.markCancelled(Fixtures.T0);
        runs.save(ancient);
        Clock clock = Clock.fixed(Fixtures.T0.plus(Duration.ofDays(365)), ZoneOffset.UTC);

        RetentionService disabled = new RetentionService(runs, events, artifactStore, 0, clock);

        assertThat(disabled.purgeExpired()).isEmpty();
        assertThat(runs.findById("run-1")).isPresent();
    }

    @Test
    void purgesExpiredTerminalRunsWithTheirEventsAndArtifacts() {
        AnalysisRun expired = new AnalysisRun("run-old", "old", "repo", "t", Fixtures.T0);
        expired.markCancelled(Fixtures.T0);
        runs.save(expired);
        AnalysisRun activeButOld = new AnalysisRun("run-live", "live", "repo", "t", Fixtures.T0);
        runs.save(activeButOld);
        events.append("run-old", "diff", "STARTED", "old", null);
        events.append("run-live", "diff", "STARTED", "live", null);
        artifactStore.save("run-old", "report.md", "# Old");

        Clock clock = Clock.fixed(Fixtures.T0.plus(Duration.ofDays(10)), ZoneOffset.UTC);
        RetentionService retention = new RetentionService(runs, events, artifactStore, 7, clock);

        assertThat(retention.purgeExpired()).containsExactly("run-old");
        assertThat(runs.findById("run-old")).isEmpty();
        assertThat(runs.findById("run-live")).isPresent();
        assertThat(events.eventsAfter("run-old", 0)).isEmpty();
        assertThat(events.eventsAfter("run-live", 0)).hasSize(1);
        assertThat(deletedArtifactRuns).containsExactly("run-old");

        // Runs younger than the window survive a second pass.
        assertThat(retention.purgeExpired()).isEmpty();
    }
}
