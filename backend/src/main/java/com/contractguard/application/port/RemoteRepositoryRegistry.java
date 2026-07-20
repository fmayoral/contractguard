package com.contractguard.application.port;

import com.contractguard.domain.RemoteRepository;

import java.util.Optional;

/** Registered remote repositories and their encrypted-at-rest credentials (FR-027). */
public interface RemoteRepositoryRegistry {

    /** Stores (or replaces) the registration; {@code token} is encrypted before it is persisted. */
    void register(RemoteRepository repository, String token);

    Optional<RemoteRepository> find(String repositoryId);

    /** Decrypts and returns the stored credential; empty if the repository is not registered. */
    Optional<String> credentialFor(String repositoryId);
}
