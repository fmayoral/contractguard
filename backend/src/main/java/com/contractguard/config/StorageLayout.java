package com.contractguard.config;

import java.nio.file.Path;

/**
 * Derives the on-disk subdirectories under {@code contractguard.storage.directory}. Kept as one
 * small shared helper (rather than duplicated in every {@code @Configuration} class that needs a
 * path) so the three directories' relationship to the storage root stays in a single place.
 */
final class StorageLayout {

    private StorageLayout() {
    }

    /**
     * The remote-repository clone cache is itself a workspace root, so once a remote repo is
     * cloned there it is an ordinary local repository to every other adapter -- diff, search and
     * execution need no remote-aware branch (ADR-0007).
     */
    static Path remoteCache(ContractGuardProperties properties) {
        return Path.of(properties.storage().directory()).resolve("remote-cache");
    }

    /**
     * Deliberately never added to {@link com.contractguard.application.policy.WorkspacePolicy}'s
     * roots, unlike {@link #remoteCache} -- a spec-source repository must never become selectable
     * as an analysable consumer repository (FR-043, ADR-0012).
     */
    static Path specSourceCache(ContractGuardProperties properties) {
        return Path.of(properties.storage().directory()).resolve("spec-source-cache");
    }

    static Path uploadedSpecs(ContractGuardProperties properties) {
        return Path.of(properties.storage().directory()).resolve("uploaded-specs");
    }
}
