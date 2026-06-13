package com.contractguard.application.port;

import com.contractguard.domain.ApiChange;

import java.nio.file.Path;
import java.util.List;

/**
 * Deterministic OpenAPI comparison (FR-003). The returned changes are
 * authoritative facts; nothing downstream may add, remove or alter them.
 */
public interface OpenApiDiffPort {

    DiffResult diff(Path oldSpec, Path newSpec);

    /**
     * @param warnings human-readable notes about spec regions the engine does
     *                 not analyse; surfaced in reports as limitations
     */
    record DiffResult(List<ApiChange> changes, List<String> warnings, String oldSpecHash, String newSpecHash) {
        public DiffResult {
            changes = List.copyOf(changes);
            warnings = List.copyOf(warnings);
        }
    }
}
