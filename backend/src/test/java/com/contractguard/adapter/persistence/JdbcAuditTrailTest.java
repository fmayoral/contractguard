package com.contractguard.adapter.persistence;

import com.contractguard.domain.AuditEntry;
import com.contractguard.domain.AuditEventType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditTrailTest {

    private static final Instant T0 = Instant.parse("2026-07-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-20T10:05:00Z");

    private JdbcAuditTrail auditTrail;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dataSource.setUser("sa");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/h2/V3__audit_log.sql"));
        }
        auditTrail = new JdbcAuditTrail(new JdbcTemplate(dataSource));
    }

    @Test
    void recordedEntriesSurviveARoundTrip() {
        AuditEntry entry = new AuditEntry("audit-1", "run-1", "customer-consumer", "test-operator",
                AuditEventType.APPROVAL_DECISION, "Plan approved", "hash-abc", T0);

        auditTrail.append(entry);

        assertThat(auditTrail.findByRun("run-1")).containsExactly(entry);
        assertThat(auditTrail.findByRepository("customer-consumer")).containsExactly(entry);
        assertThat(auditTrail.findAll()).containsExactly(entry);
    }

    @Test
    void entriesAreOrderedByOccurredAt() {
        AuditEntry second = new AuditEntry("audit-2", "run-1", "customer-consumer", "test-operator",
                AuditEventType.STATE_TRANSITION, "SUCCEEDED -> PUBLISHING", null, T1);
        AuditEntry first = new AuditEntry("audit-1", "run-1", "customer-consumer", "test-operator",
                AuditEventType.STATE_TRANSITION, "CREATED -> VALIDATING_INPUT", null, T0);

        // Recorded out of order; findByRun must still return them oldest-first.
        auditTrail.append(second);
        auditTrail.append(first);

        assertThat(auditTrail.findByRun("run-1")).extracting(AuditEntry::id)
                .containsExactly("audit-1", "audit-2");
    }

    @Test
    void unrelatedRunsAndRepositoriesAreNotReturned() {
        auditTrail.append(new AuditEntry("audit-1", "run-1", "customer-consumer", "test-operator",
                AuditEventType.STATE_TRANSITION, "CREATED -> VALIDATING_INPUT", null, T0));

        assertThat(auditTrail.findByRun("run-2")).isEmpty();
        assertThat(auditTrail.findByRepository("other-repo")).isEmpty();
        assertThat(auditTrail.findAll()).hasSize(1);
    }
}
