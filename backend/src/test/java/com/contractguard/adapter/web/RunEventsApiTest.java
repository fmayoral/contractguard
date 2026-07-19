package com.contractguard.adapter.web;

import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Fixtures;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SSE and event-log endpoints against real in-memory stores (FR-019). */
class RunEventsApiTest {

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        ArtifactStore artifacts = new ArtifactStore() {
            @Override
            public String save(String runId, String name, String content) {
                return name;
            }

            @Override
            public Optional<String> read(String runId, String artifactId) {
                return Optional.empty();
            }

            @Override
            public void deleteForRun(String runId) {
                // nothing stored
            }
        };
        RunQueryService queries = new RunQueryService(runs, events, artifacts);
        mvc = MockMvcBuilders.standaloneSetup(new RunEventsController(queries))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private AnalysisRun terminalRunWithEvents() {
        AnalysisRun run = Fixtures.newRun();
        run.markCancelled(Fixtures.T0);
        runs.save(run);
        events.append(run.id(), "diff", "STARTED", "Comparing specifications", "{\"kind\":\"tool\"}");
        events.append(run.id(), "diff", "COMPLETED", "4 change(s) detected", "{\"kind\":\"tool\"}");
        return run;
    }

    @Test
    void sseStreamReplaysPersistedEventsWithIds() throws Exception {
        AnalysisRun run = terminalRunWithEvents();

        mvc.perform(get("/api/runs/{id}/events", run.id())
                        .accept("text/event-stream"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.stringContainsInOrder(
                        "id:1", "event:run-event", "Comparing specifications",
                        "id:2", "4 change(s) detected")));
    }

    @Test
    void sseReplayHonoursLastEventId() throws Exception {
        AnalysisRun run = terminalRunWithEvents();

        mvc.perform(get("/api/runs/{id}/events", run.id())
                        .accept("text/event-stream")
                        .header("Last-Event-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("4 change(s) detected"),
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Comparing specifications")))));
    }

    @Test
    void eventListServesHistoryWithAfterFilter() throws Exception {
        AnalysisRun run = terminalRunWithEvents();

        mvc.perform(get("/api/runs/{id}/events/list", run.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
        mvc.perform(get("/api/runs/{id}/events/list?after=1", run.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].seq").value(2));
    }

    @Test
    void unknownRunGets404() throws Exception {
        mvc.perform(get("/api/runs/ghost/events/list"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeRunStreamsLiveEventsUntilTerminalEvent() throws Exception {
        AnalysisRun run = Fixtures.newRun();
        runs.save(run);
        events.append(run.id(), "diff", "STARTED", "Comparing specifications", null);

        var result = mvc.perform(get("/api/runs/{id}/events", run.id())
                        .accept("text/event-stream"))
                .andExpect(status().isOk())
                .andReturn();

        // Events appended after connecting flow through the live subscription;
        // the terminal run event completes the emitter.
        events.append(run.id(), "search", "COMPLETED", "3 evidence match(es) collected", null);
        events.append(run.id(), "run", "FAILED", "boom", null);

        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("Comparing specifications")
                .contains("3 evidence match(es) collected")
                .contains("boom");
    }
}
