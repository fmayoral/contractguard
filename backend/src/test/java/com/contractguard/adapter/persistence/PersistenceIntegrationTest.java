package com.contractguard.adapter.persistence;

import com.contractguard.application.port.RunEventLog;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RunState;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceIntegrationTest {

    private JdbcTemplate jdbc;
    private JdbcRunRepository repository;
    private JdbcRunEventLog eventLog;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
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
        assertThat(restored.evidence()).usingRecursiveComparison().isEqualTo(run.evidence());
        assertThat(restored.assessments()).usingRecursiveComparison().isEqualTo(run.assessments());
        assertThat(restored.plan().orElseThrow()).usingRecursiveComparison()
                .isEqualTo(run.plan().orElseThrow());
        assertThat(restored.approval().orElseThrow()).isEqualTo(run.approval().orElseThrow());
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
    void activeRunsAreFoundByRepository() {
        AnalysisRun active = Fixtures.newRun();
        repository.save(active);
        AnalysisRun finished = new AnalysisRun("run-2", "other", "customer-consumer", "t", Fixtures.T0);
        finished.markCancelled(Fixtures.T0);
        repository.save(finished);

        List<AnalysisRun> found = repository.findActiveByRepository("customer-consumer");

        assertThat(found).extracting(AnalysisRun::id).containsExactly(active.id());
        assertThat(repository.findActiveByRepository("other-repo")).isEmpty();
        assertThat(repository.findById("ghost")).isEmpty();
    }

    @Test
    void eventsAppendReplayAndNotifySubscribers() throws Exception {
        List<RunEventLog.RunEvent> received = new CopyOnWriteArrayList<>();
        AutoCloseable subscription = eventLog.subscribe("run-1", received::add);

        RunEventLog.RunEvent first = eventLog.append("run-1", "diff", "STARTED", "Comparing", null);
        RunEventLog.RunEvent second = eventLog.append("run-1", "diff", "COMPLETED", "Done",
                "{\"kind\":\"tool\"}");
        eventLog.append("other-run", "diff", "STARTED", "Unrelated", null);

        assertThat(second.seq()).isGreaterThan(first.seq());
        assertThat(received).hasSize(2);

        // Replay from a given sequence, scoped to the run.
        List<RunEventLog.RunEvent> replay = eventLog.eventsAfter("run-1", first.seq());
        assertThat(replay).hasSize(1);
        assertThat(replay.get(0).message()).isEqualTo("Done");
        assertThat(replay.get(0).metadataJson()).contains("tool");

        subscription.close();
        eventLog.append("run-1", "search", "STARTED", "After unsubscribe", null);
        assertThat(received).hasSize(2);
    }
}
