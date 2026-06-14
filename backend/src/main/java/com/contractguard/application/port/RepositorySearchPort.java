package com.contractguard.application.port;

import java.util.List;

/**
 * Deterministic text search over a registered repository (FR-006).
 * Matches carry exact file/line coordinates; the caller turns them into
 * {@link com.contractguard.domain.ImpactEvidence} tied to a change.
 */
public interface RepositorySearchPort {

    List<SearchMatch> search(String repositoryId, String query, String glob, int maxResults);

    record SearchMatch(String relativePath, int lineNumber, String lineText) {
    }
}
