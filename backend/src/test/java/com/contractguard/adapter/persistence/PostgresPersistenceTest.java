package com.contractguard.adapter.persistence;

import com.contractguard.application.port.RunEventLog;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RunState;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the persistence adapters against real PostgreSQL via Testcontainers.
 * Excluded from the default build (needs Docker); enable with {@code -Ppg}.
 */
@Testcontainers
class PostgresPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcRunRepository repository;
    private JdbcRunEventLog eventLog;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgresql")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM run_events");
        jdbc.update("DELETE FROM runs");
        repository = new JdbcRunRepository(jdbc);
        eventLog = new JdbcRunEventLog(jdbc, Clock.systemUTC());
    }

    @Test
    void fullAggregateSurvivesARoundTrip() {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        run.recordApproval(new Approval(run.id(), run.plan().orElseThrow().hash(),
                Approval.Decision.APPROVED, Fixtures.T0), Fixtures.T0);

        repository.save(run);
        AnalysisRun restored = repository.findById(run.id()).orElseThrow();

        assertThat(restored.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(restored.changes()).usingRecursiveComparison().isEqualTo(run.changes());
        assertThat(restored.plan().orElseThrow()).usingRecursiveComparison()
                .isEqualTo(run.plan().orElseThrow());
        assertThat(restored.isApproved()).isTrue();
    }

    @Test
    void savingTwiceUpdatesInsteadOfDuplicating() {
        AnalysisRun run = Fixtures.newRun();
        repository.save(run);
        run.transitionTo(RunState.VALIDATING_INPUT, Fixtures.T0);
        repository.save(run);

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findById(run.id()).orElseThrow().state())
                .isEqualTo(RunState.VALIDATING_INPUT);
    }

    @Test
    void eventsGetSequentialGeneratedKeys() {
        RunEventLog.RunEvent first = eventLog.append("run-1", "diff", "STARTED", "Comparing", null);
        RunEventLog.RunEvent second = eventLog.append("run-1", "diff", "COMPLETED", "Done",
                "{\"kind\":\"tool\"}");

        assertThat(second.seq()).isGreaterThan(first.seq());
        assertThat(eventLog.eventsAfter("run-1", first.seq())).hasSize(1);
    }

    @Test
    void retentionDeletesOnlyOldTerminalRuns() {
        AnalysisRun finished = new AnalysisRun("run-old", "old", "repo", "t", Fixtures.T0);
        finished.markCancelled(Fixtures.T0);
        repository.save(finished);
        AnalysisRun active = new AnalysisRun("run-live", "live", "repo", "t", Fixtures.T0);
        repository.save(active);
        eventLog.append("run-old", "diff", "STARTED", "old event", null);

        List<String> deleted = repository.deleteFinishedBefore(Fixtures.T0.plusSeconds(60));

        assertThat(deleted).containsExactly("run-old");
        assertThat(repository.findById("run-old")).isEmpty();
        assertThat(repository.findById("run-live")).isPresent();

        eventLog.deleteForRun("run-old");
        assertThat(eventLog.eventsAfter("run-old", 0)).isEmpty();
    }
}
