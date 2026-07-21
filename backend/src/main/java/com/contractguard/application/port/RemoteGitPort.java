package com.contractguard.application.port;

import com.contractguard.domain.RemoteRepository;

/**
 * Credentialed network git operations against a registered remote (FR-027).
 * Distinct from {@link GitWorkspacePort}, which never needs a credential.
 */
public interface RemoteGitPort {

    /**
     * Ensures a local clone of {@code remote}'s default branch exists and is
     * up to date, cloning on first use and hard-resetting to
     * {@code origin/<defaultBranch>} on subsequent calls.
     */
    void cloneOrRefresh(String repositoryId, RemoteRepository remote, String credential);

    /** Pushes {@code branchName} to {@code origin}, creating it on the remote if needed. */
    void push(String repositoryId, String branchName, RemoteRepository remote, String credential);

    /**
     * Removes the local clone cache for {@code repositoryId}, if any (FR-044). Called on
     * deregistration so a future re-registration under the same ID (e.g. with a corrected
     * credential or a different clone URL) starts from a fresh clone rather than reusing one
     * that may point at the wrong remote.
     */
    void deleteLocalClone(String repositoryId);
}
