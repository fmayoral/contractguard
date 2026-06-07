package com.contractguard.domain;

import java.util.Objects;

/**
 * A single normalised contract change produced by the deterministic diff.
 * The LLM may attach an {@code explanation} but can never alter the facts.
 *
 * @param method      HTTP method, null for schema-only changes
 * @param path        endpoint path, null for schema-only changes
 * @param schema      schema name, null for endpoint-only changes
 * @param property    property name, null unless a property is affected
 * @param oldValue    previous value (path, name, type or enum constant)
 * @param newValue    new value, null for removals
 * @param reason      machine-readable classification reason code
 * @param rawEvidence machine-readable diff evidence (deterministic origin)
 * @param explanation optional LLM-authored explanation of the change
 */
public record ApiChange(
        String id,
        ChangeType type,
        Classification classification,
        String method,
        String path,
        String schema,
        String property,
        String oldValue,
        String newValue,
        String reason,
        String rawEvidence,
        String explanation) {

    public ApiChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(rawEvidence, "rawEvidence");
    }

    public ApiChange withExplanation(String newExplanation) {
        return new ApiChange(id, type, classification, method, path, schema, property,
                oldValue, newValue, reason, rawEvidence, newExplanation);
    }
}
