package com.contractguard.application.port;

import java.util.Optional;

/** Run-scoped artifact storage for tool outputs, patches, logs and reports (§15). */
public interface ArtifactStore {

    /** @return artifact ID usable with {@link #read} */
    String save(String runId, String name, String content);

    Optional<String> read(String runId, String artifactId);

    /** Removes every artifact of a run; used by retention (FR-023). */
    void deleteForRun(String runId);
}
