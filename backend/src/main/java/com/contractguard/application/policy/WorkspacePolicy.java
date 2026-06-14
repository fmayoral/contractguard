package com.contractguard.application.policy;

import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Security boundary for every filesystem access (§17). Repositories must be
 * direct children of a configured workspace root; file access is confined to
 * the repository via canonical-path containment (which also defeats symlink
 * escapes), and likely secret files are blocked outright.
 */
public final class WorkspacePolicy {

    private static final List<String> BLOCKED_NAME_PARTS =
            List.of("secret", "credential", "id_rsa", "id_ed25519", ".netrc", ".npmrc", ".env");
    private static final List<String> BLOCKED_EXTENSIONS =
            List.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".asc", ".gpg");

    private final List<Path> roots;

    public WorkspacePolicy(List<Path> configuredRoots) {
        this.roots = new ArrayList<>();
        for (Path root : configuredRoots) {
            this.roots.add(root.toAbsolutePath().normalize());
        }
        if (this.roots.isEmpty()) {
            throw new IllegalArgumentException("at least one workspace root must be configured");
        }
    }

    /** Repositories registered = direct child directories of a root containing a Git repository. */
    public List<String> listRepositories() {
        List<String> ids = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(dir -> Files.isDirectory(dir.resolve(".git")))
                        .map(dir -> dir.getFileName().toString())
                        .sorted()
                        .forEach(ids::add);
            } catch (IOException e) {
                throw new ContractGuardException(new com.contractguard.domain.RunFailure(
                        FailureCategory.INTERNAL_ERROR,
                        "cannot list workspace root " + root, false, null,
                        "Check filesystem permissions on the workspace root."), e);
            }
        }
        return ids;
    }

    /** Resolves a repository ID to its canonical directory, rejecting anything outside the roots. */
    public Path resolveRepository(String repositoryId) {
        if (repositoryId.contains("/") || repositoryId.contains("\\") || repositoryId.contains("..")) {
            throw outsideWorkspace(repositoryId);
        }
        for (Path root : roots) {
            Path candidate = root.resolve(repositoryId);
            if (Files.isDirectory(candidate)) {
                Path canonical = canonical(candidate);
                if (canonical.startsWith(canonical(root))) {
                    return canonical;
                }
            }
        }
        throw outsideWorkspace(repositoryId);
    }

    /**
     * Resolves a relative file path against a repository, enforcing canonical
     * containment. The canonical path of an existing file follows symlinks,
     * so a link pointing outside the repository fails the containment check.
     */
    public Path resolveFile(Path repositoryRoot, String relativePath) {
        Path resolved = repositoryRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(repositoryRoot)) {
            throw escape(relativePath);
        }
        if (Files.exists(resolved)) {
            Path canonical = canonical(resolved);
            if (!canonical.startsWith(canonical(repositoryRoot))) {
                throw escape(relativePath);
            }
            return canonical;
        }
        return resolved;
    }

    /** True for paths that likely hold secrets or Git internals; such files are never read or patched. */
    public boolean isBlockedFile(String relativePath) {
        String normalised = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalised.startsWith(".git/") || normalised.contains("/.git/")) {
            return true;
        }
        String fileName = normalised.substring(normalised.lastIndexOf('/') + 1);
        for (String part : BLOCKED_NAME_PARTS) {
            if (fileName.contains(part)) {
                return true;
            }
        }
        for (String extension : BLOCKED_EXTENSIONS) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    public void requireReadableFile(String relativePath) {
        if (isBlockedFile(relativePath)) {
            throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                    "access to likely secret file blocked: " + relativePath,
                    "Secret-like files are never read, sent to the model, or patched.");
        }
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new ContractGuardException(new com.contractguard.domain.RunFailure(
                    FailureCategory.POLICY_VIOLATION,
                    "cannot canonicalise path " + path, false, null,
                    "Check that the path exists and is accessible."), e);
        }
    }

    private static ContractGuardException outsideWorkspace(String repositoryId) {
        return ContractGuardException.of(FailureCategory.REPOSITORY_OUTSIDE_WORKSPACE,
                "repository '%s' is not a registered workspace repository".formatted(repositoryId),
                "Place the repository directly under a configured workspace root.");
    }

    private static ContractGuardException escape(String relativePath) {
        return ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                "path escapes the repository: " + relativePath,
                "Only paths inside the repository are accessible.");
    }
}
