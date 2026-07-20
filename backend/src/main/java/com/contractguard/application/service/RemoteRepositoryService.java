package com.contractguard.application.service;

import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RemoteRepository;

import java.time.Clock;
import java.util.Optional;

/**
 * Registers remote repositories and keeps their local clone cache current
 * (FR-027). The cache directory is itself a configured workspace root, so
 * once cloned a remote repository is indistinguishable from a local one to
 * the rest of the pipeline (diff, search, execution) — see ADR-0007.
 */
public class RemoteRepositoryService {

    private final RemoteRepositoryRegistry registry;
    private final RemoteGitPort remoteGit;
    private final Clock clock;

    public RemoteRepositoryService(RemoteRepositoryRegistry registry, RemoteGitPort remoteGit, Clock clock) {
        this.registry = registry;
        this.remoteGit = remoteGit;
        this.clock = clock;
    }

    public RemoteRepository register(String repositoryId, String cloneUrl, String defaultBranch, String token) {
        if (repositoryId == null || repositoryId.isBlank() || repositoryId.contains("/")
                || repositoryId.contains("\\") || repositoryId.contains("..")) {
            throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                    "invalid repository id: " + repositoryId,
                    "Use a simple name with no path separators.");
        }
        RemoteRepository remote =
                RemoteRepository.forGitHub(repositoryId, cloneUrl, defaultBranch, clock.instant());
        registry.register(remote, token);
        return remote;
    }

    /** No-op when {@code repositoryId} is not remote-registered; local workspace repos flow through untouched. */
    public void ensureLocalClone(String repositoryId) {
        Optional<RemoteRepository> remote = registry.find(repositoryId);
        if (remote.isEmpty()) {
            return;
        }
        String credential = registry.credentialFor(repositoryId).orElseThrow(() ->
                ContractGuardException.of(FailureCategory.CREDENTIAL_KEY_NOT_CONFIGURED,
                        "no stored credential for repository '%s'".formatted(repositoryId),
                        "Re-register the repository with a credential."));
        remoteGit.cloneOrRefresh(repositoryId, remote.get(), credential);
    }

    public boolean isRemote(String repositoryId) {
        return registry.find(repositoryId).isPresent();
    }
}
