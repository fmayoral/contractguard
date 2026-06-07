package com.contractguard.domain;

import java.util.Objects;

/**
 * A deterministic repository match tying an {@link ApiChange} to a concrete
 * file location. Every impact claim must reference at least one of these.
 */
public record ImpactEvidence(
        String id,
        String apiChangeId,
        String relativePath,
        int startLine,
        int endLine,
        String snippet,
        String searchTerm,
        String relationship,
        String contentHash) {

    public ImpactEvidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(apiChangeId, "apiChangeId");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(snippet, "snippet");
        Objects.requireNonNull(searchTerm, "searchTerm");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(contentHash, "contentHash");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException(
                    "invalid line range %d-%d for %s".formatted(startLine, endLine, relativePath));
        }
    }
}
