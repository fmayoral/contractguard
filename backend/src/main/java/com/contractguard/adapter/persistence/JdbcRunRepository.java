package com.contractguard.adapter.persistence;

import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** H2-backed run store: one aggregate per row as a versioned JSON document (ADR-0004). */
public class JdbcRunRepository implements RunRepository {

    private static final List<String> TERMINAL_STATES =
            List.of("SUCCEEDED", "FAILED", "REJECTED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RowMapper<AnalysisRun> rowMapper =
            (rs, rowNum) -> fromJson(rs.getString("payload"));

    public JdbcRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AnalysisRun run) {
        String payload = toJson(RunDocument.fromDomain(run));
        jdbc.update("""
                MERGE INTO runs (id, name, state, repository_id, created_at, updated_at,
                                 payload_version, payload)
                KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                run.id(), run.name(), run.state().name(), run.repositoryId(),
                Timestamp.from(run.createdAt()), Timestamp.from(run.updatedAt()),
                RunDocument.CURRENT_VERSION, payload);
    }

    @Override
    public Optional<AnalysisRun> findById(String runId) {
        List<AnalysisRun> found = jdbc.query(
                "SELECT payload FROM runs WHERE id = ?", rowMapper, runId);
        return found.stream().findFirst();
    }

    @Override
    public List<AnalysisRun> findAll() {
        return jdbc.query("SELECT payload FROM runs ORDER BY created_at DESC", rowMapper);
    }

    @Override
    public List<AnalysisRun> findActiveByRepository(String repositoryId) {
        String placeholders = String.join(",", TERMINAL_STATES.stream().map(s -> "?").toList());
        Object[] args = new Object[TERMINAL_STATES.size() + 1];
        args[0] = repositoryId;
        for (int i = 0; i < TERMINAL_STATES.size(); i++) {
            args[i + 1] = TERMINAL_STATES.get(i);
        }
        return jdbc.query(
                "SELECT payload FROM runs WHERE repository_id = ? AND state NOT IN (" + placeholders + ")",
                rowMapper, args);
    }

    private String toJson(RunDocument document) {
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("run aggregate cannot be serialised", e);
        }
    }

    private AnalysisRun fromJson(String payload) {
        try {
            return mapper.readValue(payload, RunDocument.class).toDomain();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored run payload cannot be deserialised", e);
        }
    }
}
