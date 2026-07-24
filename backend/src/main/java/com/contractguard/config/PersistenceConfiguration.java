package com.contractguard.config;

import com.contractguard.adapter.artifacts.FilesystemArtifactStore;
import com.contractguard.adapter.observability.MetricsRecordingAuditTrail;
import com.contractguard.adapter.persistence.JdbcAuditTrail;
import com.contractguard.adapter.persistence.JdbcRunEventLog;
import com.contractguard.adapter.persistence.JdbcRunRepository;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.application.port.AuditTrailPort;
import com.contractguard.application.port.NotificationPort;
import com.contractguard.application.port.RunEventLog;
import com.contractguard.application.port.RunRepository;
import com.contractguard.application.service.AuditTrailService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Wires durable storage (FR-023): runs, events and artifacts behind JDBC/filesystem adapters, plus
 * the audit trail (FR-025) that survives run retention purges by design (ADR-0008).
 */
@Configuration
public class PersistenceConfiguration {

    @Bean
    public RunRepository runRepository(JdbcTemplate jdbc) {
        return new JdbcRunRepository(jdbc);
    }

    @Bean
    public RunEventLog runEventLog(JdbcTemplate jdbc, Clock clock) {
        return new JdbcRunEventLog(jdbc, clock);
    }

    @Bean
    public ArtifactStore artifactStore(ContractGuardProperties properties) {
        return new FilesystemArtifactStore(Path.of(properties.storage().directory()));
    }

    @Bean
    public AuditTrailPort auditTrailPort(JdbcTemplate jdbc, MeterRegistry meters) {
        return new MetricsRecordingAuditTrail(new JdbcAuditTrail(jdbc), meters);
    }

    @Bean
    public AuditTrailService auditTrailService(AuditTrailPort port, NotificationPort notifications,
            ContractGuardProperties properties, Clock clock) {
        return new AuditTrailService(port, notifications, properties.audit().defaultPrincipal(), clock);
    }
}
