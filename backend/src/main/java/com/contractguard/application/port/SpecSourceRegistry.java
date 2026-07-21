package com.contractguard.application.port;

import com.contractguard.domain.RemoteRepository;

import java.util.List;
import java.util.Optional;

/**
 * Registered specification-source repositories, separate from
 * {@link RemoteRepositoryRegistry} (FR-043, ADR-0012): a spec source is
 * read-only and never mutated, so it is never mistaken for an analysable
 * consumer repository. {@code token} may be blank for a public repository.
 */
public interface SpecSourceRegistry {

    /** Stores (or replaces) the registration; a non-blank {@code token} is encrypted before it is persisted. */
    void register(RemoteRepository repository, String token);

    Optional<RemoteRepository> find(String repositoryId);

    /** Decrypts and returns the stored credential; empty if none was supplied at registration. */
    Optional<String> credentialFor(String repositoryId);

    /** All registrations, ordered by repository ID; credentials are never included. */
    List<RemoteRepository> findAll();

    /** Removes the registration and its credential; a no-op if it was never registered (FR-044). */
    void deregister(String repositoryId);
}
