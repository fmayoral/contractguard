package com.contractguard.application.port;

import java.util.List;

/**
 * Read/branch operations on a workspace repository (FR-011/FR-012). The MVP
 * never commits, merges, pushes, rebases, resets, tags or deletes branches —
 * no such operation exists on this port by design.
 */
public interface GitWorkspacePort {

    GitStatus status(String repositoryId);

    boolean branchExists(String repositoryId, String branchName);

    /** Creates and checks out the branch; the caller records the original branch from {@link #status}. */
    void createBranch(String repositoryId, String branchName);

    record GitStatus(String currentBranch, boolean clean, List<String> dirtyEntries) {
    }
}
