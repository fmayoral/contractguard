package com.contractguard.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One append-only, principal-attributed compliance fact (FR-025) — a state
 * transition, an approval decision, or a repository mutation. Distinct from
 * the operational {@link com.contractguard.application.port.RunEventLog}
 * timeline: the audit trail is immutable through the API and survives the
 * retention policy that purges finished runs and their events (ADR-0008).
 */
public record AuditEntry(String id, String runId, String repositoryId, String principal,
        AuditEventType eventType, String detail, String planHash, Instant occurredAt) {

    public AuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
