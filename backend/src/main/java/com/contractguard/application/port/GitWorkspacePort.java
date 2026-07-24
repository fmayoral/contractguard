package com.contractguard.application.port;

import java.util.List;
import java.util.Set;

/**
 * Read/branch/commit operations on a workspace repository (FR-011/FR-012).
 * Commit is local-only, using a fixed bot identity (ADR-0007); this port
 * never merges, rebases, resets, tags, deletes branches or touches a remote
 * — publishing to a remote is a separate, credentialed capability
 * ({@link RemoteGitPort}).
 */
public interface GitWorkspacePort {

    GitStatus status(String repositoryId);

    boolean branchExists(String repositoryId, String branchName);

    /** Creates and checks out the branch; the caller records the original branch from {@link #status}. */
    void createBranch(String repositoryId, String branchName);

    /**
     * Stages exactly {@code paths} and commits them under a fixed ContractGuard bot identity
     * (FR-027) — deliberately never {@code git add -A}: an incidental working-tree change
     * unrelated to the approved patch (e.g. {@code mvnw}'s executable bit, flipped by validation
     * so the wrapper can even run) must never ride along into the committed diff.
     */
    void commit(String repositoryId, String message, Set<String> paths);

    record GitStatus(String currentBranch, boolean clean, List<String> dirtyEntries) {
    }
}
