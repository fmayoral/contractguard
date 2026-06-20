package com.contractguard.testsupport;

import com.contractguard.application.port.RunEventLog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** In-memory event log for application-service tests. */
public class InMemoryRunEventLog implements RunEventLog {

    private final List<RunEvent> events = new CopyOnWriteArrayList<>();
    private final List<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public RunEvent append(String runId, String step, String status, String message, String metadataJson) {
        RunEvent event = new RunEvent(runId, sequence.incrementAndGet(), Instant.now(),
                step, status, message, metadataJson);
        events.add(event);
        listeners.forEach(listener -> listener.accept(event));
        return event;
    }

    @Override
    public List<RunEvent> eventsAfter(String runId, long afterSeq) {
        return events.stream()
                .filter(e -> e.runId().equals(runId) && e.seq() > afterSeq)
                .toList();
    }

    @Override
    public AutoCloseable subscribe(String runId, Consumer<RunEvent> listener) {
        Consumer<RunEvent> filtered = event -> {
            if (event.runId().equals(runId)) {
                listener.accept(event);
            }
        };
        listeners.add(filtered);
        return () -> listeners.remove(filtered);
    }

    public List<RunEvent> all() {
        return new ArrayList<>(events);
    }
}
