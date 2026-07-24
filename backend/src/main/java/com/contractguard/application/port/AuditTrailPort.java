package com.contractguard.application.port;

import com.contractguard.domain.AuditEntry;

import java.util.List;

/** Append-only audit log storage (FR-025). No update/delete method exists on this port by design. */
public interface AuditTrailPort {

    void append(AuditEntry entry);

    /** Entries for one run, oldest first. */
    List<AuditEntry> findByRun(String runId);

    /** Entries for one repository across all its runs, oldest first. */
    List<AuditEntry> findByRepository(String repositoryId);

    /** Every entry, oldest first. */
    List<AuditEntry> findAll();
}
