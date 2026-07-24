package com.contractguard.adapter.persistence;

import com.contractguard.application.port.AuditTrailPort;
import com.contractguard.domain.AuditEntry;
import com.contractguard.domain.AuditEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

/** JDBC-backed audit trail. Append-only: no update or delete method exists (ADR-0008). */
public class JdbcAuditTrail implements AuditTrailPort {

    private final JdbcTemplate jdbc;

    private final RowMapper<AuditEntry> rowMapper = (rs, rowNum) -> new AuditEntry(
            rs.getString("id"), rs.getString("run_id"), rs.getString("repository_id"),
            rs.getString("principal"), AuditEventType.valueOf(rs.getString("event_type")),
            rs.getString("detail"), rs.getString("plan_hash"), rs.getTimestamp("occurred_at").toInstant());

    public JdbcAuditTrail(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AuditEntry entry) {
        jdbc.update("""
                INSERT INTO audit_log (id, run_id, repository_id, principal, event_type,
                    detail, plan_hash, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                entry.id(), entry.runId(), entry.repositoryId(), entry.principal(),
                entry.eventType().name(), entry.detail(), entry.planHash(),
                Timestamp.from(entry.occurredAt()));
    }

    @Override
    public List<AuditEntry> findByRun(String runId) {
        return jdbc.query(
                "SELECT * FROM audit_log WHERE run_id = ? ORDER BY occurred_at, id",
                rowMapper, runId);
    }

    @Override
    public List<AuditEntry> findByRepository(String repositoryId) {
        return jdbc.query(
                "SELECT * FROM audit_log WHERE repository_id = ? ORDER BY occurred_at, id",
                rowMapper, repositoryId);
    }

    @Override
    public List<AuditEntry> findAll() {
        return jdbc.query("SELECT * FROM audit_log ORDER BY occurred_at, id", rowMapper);
    }
}
