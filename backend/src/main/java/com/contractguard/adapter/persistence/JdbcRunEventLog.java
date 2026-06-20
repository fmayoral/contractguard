package com.contractguard.adapter.persistence;

import com.contractguard.application.port.RunEventLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Durable event log with in-process fan-out for SSE (FR-019). Persisted rows
 * provide `Last-Event-ID` replay; subscribers get every event appended after
 * they attach.
 */
public class JdbcRunEventLog implements RunEventLog {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, List<Consumer<RunEvent>>> subscribers = new ConcurrentHashMap<>();

    private final RowMapper<RunEvent> rowMapper = (rs, rowNum) -> new RunEvent(
            rs.getString("run_id"), rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("step"), rs.getString("status"), rs.getString("message"),
            rs.getString("metadata"));

    public JdbcRunEventLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public RunEvent append(String runId, String step, String status, String message, String metadataJson) {
        Timestamp occurredAt = Timestamp.from(clock.instant());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO run_events (run_id, occurred_at, step, status, message, metadata)
                    VALUES (?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, runId);
            ps.setTimestamp(2, occurredAt);
            ps.setString(3, step);
            ps.setString(4, status);
            ps.setString(5, message);
            ps.setString(6, metadataJson);
            return ps;
        }, keyHolder);
        long seq = Objects.requireNonNull(keyHolder.getKey(), "generated event id").longValue();
        RunEvent event = new RunEvent(runId, seq, occurredAt.toInstant(), step, status, message, metadataJson);
        for (Consumer<RunEvent> listener : subscribers.getOrDefault(runId, List.of())) {
            listener.accept(event);
        }
        return event;
    }

    @Override
    public List<RunEvent> eventsAfter(String runId, long afterSeq) {
        return jdbc.query("""
                SELECT id, run_id, occurred_at, step, status, message, metadata
                FROM run_events WHERE run_id = ? AND id > ? ORDER BY id""",
                rowMapper, runId, afterSeq);
    }

    @Override
    public AutoCloseable subscribe(String runId, Consumer<RunEvent> listener) {
        List<Consumer<RunEvent>> list =
                subscribers.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }
}
