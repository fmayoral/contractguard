package com.contractguard.application.port;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Append-only per-run event log powering the timeline, SSE streaming and
 * reconnect replay (FR-019). Sequence numbers are assigned by the store and
 * strictly increase per run.
 */
public interface RunEventLog {

    // The fixed vocabulary for metadataJson's "kind" tag -- shared here rather than
    // re-typed as a raw literal at every call site across the pipeline/execution/publish services.
    String KIND_TOOL = "{\"kind\":\"tool\"}";
    String KIND_LLM = "{\"kind\":\"llm\"}";
    String KIND_SYSTEM = "{\"kind\":\"system\"}";
    String KIND_HUMAN = "{\"kind\":\"human\"}";

    RunEvent append(String runId, String step, String status, String message, String metadataJson);

    List<RunEvent> eventsAfter(String runId, long afterSeq);

    /** Live subscription for SSE; the returned handle unsubscribes on close. */
    AutoCloseable subscribe(String runId, Consumer<RunEvent> listener);

    /** Removes all persisted events of a run; used by retention (FR-023). */
    void deleteForRun(String runId);

    record RunEvent(String runId, long seq, Instant occurredAt, String step, String status,
            String message, String metadataJson) {
    }
}
