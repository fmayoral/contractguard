package com.contractguard.application.service;

import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.SpecSourceRegistry;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RemoteRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Registers read-only specification-source repositories and lists the spec
 * files they contain (FR-043, ADR-0012). Unlike consumer repositories, a
 * spec source is never cloned into a {@code WorkspacePolicy} root — it must
 * never become selectable/searchable as an analysable consumer repository.
 */
public class SpecSourceService {

    private final SpecSourceRegistry registry;
    private final RemoteGitPort remoteGit;
    private final Path cacheRoot;
    private final Clock clock;

    public SpecSourceService(SpecSourceRegistry registry, RemoteGitPort remoteGit, Path cacheRoot, Clock clock) {
        this.registry = registry;
        this.remoteGit = remoteGit;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** @param token may be blank; a public repository needs no credential (ADR-0012 decision #2). */
    public RemoteRepository register(String repositoryId, String cloneUrl, String defaultBranch, String token) {
        if (repositoryId == null || repositoryId.isBlank() || repositoryId.contains("/")
                || repositoryId.contains("\\") || repositoryId.contains("..")) {
            throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                    "invalid repository id: " + repositoryId,
                    "Use a simple name with no path separators.");
        }
        RemoteRepository source = RemoteRepository.forGitHub(repositoryId, cloneUrl, defaultBranch, clock.instant());
        registry.register(source, token);
        return source;
    }

    public List<RemoteRepository> listRegistered() {
        return registry.findAll();
    }

    /** Removes the registration and its local clone cache (FR-044). A no-op if never registered. */
    public void deregister(String repositoryId) {
        registry.deregister(repositoryId);
        remoteGit.deleteLocalClone(repositoryId);
    }

    /**
     * Clone-or-refreshes every registered source (mirrors
     * {@code RemoteRepositoryService.ensureLocalClone}'s lazy-refresh pattern
     * — always current, no separate "refresh" endpoint needed) and lists its
     * specification files. A source that fails to clone or refresh (revoked
     * token, network issue) is skipped rather than failing the whole listing.
     */
    public List<SpecSourceFile> listSpecFiles() {
        List<SpecSourceFile> files = new ArrayList<>();
        for (RemoteRepository source : registry.findAll()) {
            try {
                String credential = registry.credentialFor(source.repositoryId()).orElse("");
                remoteGit.cloneOrRefresh(source.repositoryId(), source, credential);
                files.addAll(listFilesIn(source.repositoryId()));
            } catch (RuntimeException e) {
                // Skip this source; the rest of the setup listing must still work (ADR-0012 decision #4).
                // The failure itself is logged by the adapter (RemoteGitCliAdapter), not here -- this
                // layer stays framework-free, so it cannot hold a logger itself.
            }
        }
        return files;
    }

    private List<SpecSourceFile> listFilesIn(String repositoryId) {
        Path directory = cacheRoot.resolve(repositoryId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(directory)) {
            return list.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json"))
                    .sorted()
                    .map(name -> new SpecSourceFile(repositoryId, name))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** @return the local path of a file previously offered by {@link #listSpecFiles()}, if it still exists */
    public Path resolve(String repositoryId, String fileName) {
        Path directory = cacheRoot.resolve(repositoryId);
        Path resolved = directory.resolve(fileName).normalize();
        if (!resolved.startsWith(directory) || !Files.isRegularFile(resolved)) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "specification file not found in spec source '%s': %s".formatted(repositoryId, fileName),
                    "Choose a file from the spec source listing.");
        }
        return resolved;
    }

    public record SpecSourceFile(String sourceId, String fileName) {
    }
}
