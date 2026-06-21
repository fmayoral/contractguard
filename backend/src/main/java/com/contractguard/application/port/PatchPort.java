package com.contractguard.application.port;

import java.util.List;
import java.util.Map;

/**
 * Patch construction, safety checking and application (FR-013/FR-014).
 * Diffs are always computed by the backend from complete file contents;
 * checking uses real {@code git apply --check} semantics.
 */
public interface PatchPort {

    /**
     * Computes a minimal unified diff between the files' current on-disk
     * content and the given new contents. Paths are repo-relative with
     * forward slashes; unchanged files produce no hunks.
     */
    String buildUnifiedDiff(String repositoryId, Map<String, String> newContents);

    PatchCheck check(String repositoryId, String unifiedDiff);

    /** Applies a diff that must have passed {@link #check} immediately before. */
    void apply(String repositoryId, String unifiedDiff);

    record PatchCheck(boolean valid, List<String> changedPaths, List<String> rejections,
            int addedLines, int removedLines) {
    }
}
