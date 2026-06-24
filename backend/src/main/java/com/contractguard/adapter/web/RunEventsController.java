package com.contractguard.adapter.web;

import com.contractguard.adapter.web.dto.DtoMapper;
import com.contractguard.adapter.web.dto.RunDtos;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.RunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * SSE progress streaming with `Last-Event-ID` replay (FR-019). Missed events
 * are replayed from the persisted log before live events attach, so a
 * reconnecting client never loses or duplicates progress. A JSON listing of
 * the same log serves history views.
 */
@RestController
@RequestMapping("/api/runs/{runId}/events")
public class RunEventsController {

    private static final Logger log = LoggerFactory.getLogger(RunEventsController.class);
    private static final long SSE_TIMEOUT_MILLIS = 30L * 60 * 1000;

    private final RunQueryService queries;

    public RunEventsController(RunQueryService queries) {
        this.queries = queries;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        AnalysisRun run = queries.getRun(runId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        long afterSeq = lastEventId == null ? 0 : lastEventId;

        AutoCloseable subscription = subscribeIfActive(run, emitter);
        try {
            for (var event : queries.eventsAfter(runId, afterSeq)) {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.seq()))
                        .name("run-event")
                        .data(DtoMapper.toEvent(event), MediaType.APPLICATION_JSON));
            }
            if (run.state().isTerminal() && subscription == null) {
                emitter.complete();
            }
        } catch (IOException e) {
            closeQuietly(subscription);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private AutoCloseable subscribeIfActive(AnalysisRun run, SseEmitter emitter) {
        if (run.state().isTerminal()) {
            return null;
        }
        AutoCloseable subscription = queries.subscribe(run.id(), event -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.seq()))
                        .name("run-event")
                        .data(DtoMapper.toEvent(event), MediaType.APPLICATION_JSON));
                if (isTerminalEvent(event.step(), event.status())) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                // Client went away; the emitter lifecycle callbacks handle cleanup.
                log.debug("SSE client disconnected from run {}", event.runId());
            }
        });
        emitter.onCompletion(() -> closeQuietly(subscription));
        emitter.onTimeout(() -> closeQuietly(subscription));
        emitter.onError(t -> closeQuietly(subscription));
        return subscription;
    }

    private static boolean isTerminalEvent(String step, String status) {
        return "run".equals(step) && (RunState.SUCCEEDED.name().equals(status)
                || RunState.FAILED.name().equals(status)
                || RunState.REJECTED.name().equals(status)
                || RunState.CANCELLED.name().equals(status));
    }

    /** Plain JSON view of the event log for history pages and tests. */
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RunDtos.Event> list(@PathVariable String runId,
            @RequestParam(name = "after", defaultValue = "0") long after) {
        queries.getRun(runId);
        return queries.eventsAfter(runId, after).stream().map(DtoMapper::toEvent).toList();
    }

    private static void closeQuietly(AutoCloseable subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception ignored) {
            // Unsubscribing twice is harmless.
        }
    }
}
