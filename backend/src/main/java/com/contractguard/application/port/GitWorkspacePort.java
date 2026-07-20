package com.contractguard.application.port;

import java.util.List;

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

    /** Stages and commits all working-tree changes under a fixed ContractGuard bot identity (FR-027). */
    void commit(String repositoryId, String message);

    record GitStatus(String currentBranch, boolean clean, List<String> dirtyEntries) {
    }
}
