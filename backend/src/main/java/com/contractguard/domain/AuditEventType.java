package com.contractguard.domain;

/** Categories of fact recorded in the audit trail (FR-025). */
public enum AuditEventType {
    STATE_TRANSITION,
    APPROVAL_DECISION,
    REPOSITORY_MUTATION
}
