package com.contractguard.application.service;

import com.contractguard.application.port.ArtifactStore;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.Fixtures;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunQueryServiceTest {

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private RunQueryService service;
    private final Map<String, String> artifacts = new HashMap<>();

    @BeforeEach
    void setUp() {
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        ArtifactStore store = new ArtifactStore() {
            @Override
            public String save(String runId, String name, String content) {
                artifacts.put(runId + "/" + name, content);
                return name;
            }

            @Override
            public Optional<String> read(String runId, String artifactId) {
                return Optional.ofNullable(artifacts.get(runId + "/" + artifactId));
            }
        };
        service = new RunQueryService(runs, events, store);
    }

    @Test
    void getsAndListsRuns() {
        AnalysisRun run = Fixtures.newRun();
        runs.save(run);

        assertThat(service.getRun(run.id()).id()).isEqualTo(run.id());
        assertThat(service.findRun(run.id())).isPresent();
        assertThat(service.findRun("ghost")).isEmpty();
        assertThat(service.listRuns()).hasSize(1);
    }

    @Test
    void unknownRunThrowsTyped() {
        assertThatThrownBy(() -> service.getRun("ghost"))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void exposesEventsReplayAndSubscription() throws Exception {
        events.append("run-1", "diff", "STARTED", "msg", null);
        List<RunQueryService.EventView> received = new ArrayList<>();
        AutoCloseable handle = service.subscribe("run-1", received::add);
        events.append("run-1", "diff", "COMPLETED", "done", null);
        handle.close();

        assertThat(service.eventsAfter("run-1", 0)).hasSize(2);
        assertThat(received).hasSize(1);
    }

    @Test
    void readsArtifacts() {
        artifacts.put("run-1/report.md", "# Report");
        assertThat(service.readArtifact("run-1", "report.md")).contains("# Report");
        assertThat(service.readArtifact("run-1", "missing")).isEmpty();
    }
}
