package com.contractguard.testsupport;

import com.contractguard.application.port.AuditTrailPort;
import com.contractguard.domain.AuditEntry;

import java.util.ArrayList;
import java.util.List;

/** In-memory audit trail for application-service tests. */
public class InMemoryAuditTrail implements AuditTrailPort {

    private final List<AuditEntry> entries = new ArrayList<>();

    @Override
    public void append(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<AuditEntry> findByRun(String runId) {
        return entries.stream().filter(e -> e.runId().equals(runId)).toList();
    }

    @Override
    public List<AuditEntry> findByRepository(String repositoryId) {
        return entries.stream().filter(e -> e.repositoryId().equals(repositoryId)).toList();
    }

    @Override
    public List<AuditEntry> findAll() {
        return List.copyOf(entries);
    }
}
