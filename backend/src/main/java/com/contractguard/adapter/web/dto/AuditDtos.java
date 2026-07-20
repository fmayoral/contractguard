package com.contractguard.adapter.web.dto;

import com.contractguard.domain.AuditEntry;

import java.time.Instant;
import java.util.List;

/** Wire shape for the audit trail (FR-025); deliberately read-only — no request DTO exists to mutate it. */
public final class AuditDtos {

    private AuditDtos() {
    }

    public record Entry(String id, String runId, String repositoryId, String principal,
            String eventType, String detail, String planHash, Instant occurredAt) {

        static Entry from(AuditEntry entry) {
            return new Entry(entry.id(), entry.runId(), entry.repositoryId(), entry.principal(),
                    entry.eventType().name(), entry.detail(), entry.planHash(), entry.occurredAt());
        }
    }

    public static List<Entry> from(List<AuditEntry> entries) {
        return entries.stream().map(Entry::from).toList();
    }
}
