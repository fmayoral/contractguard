package com.contractguard.adapter.search;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RunFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bounded, deterministic substring search over repository text files.
 * Git internals, blocked files, binaries and oversized files are skipped;
 * results are ordered by path then line for reproducibility.
 */
public class FilesystemRepositorySearchAdapter implements RepositorySearchPort {

    private static final long MAX_FILE_SIZE_BYTES = 1_000_000;
    private static final int MAX_LINE_LENGTH = 500;

    private final WorkspacePolicy policy;

    public FilesystemRepositorySearchAdapter(WorkspacePolicy policy) {
        this.policy = policy;
    }

    @Override
    public List<SearchMatch> search(String repositoryId, String query, String glob, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Path repoRoot = policy.resolveRepository(repositoryId);
        PathMatcher matcher = glob == null || glob.isBlank()
                ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<SearchMatch> matches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repoRoot)) {
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .map(repoRoot::relativize)
                    .filter(rel -> !policy.isBlockedFile(rel.toString()))
                    .filter(rel -> matcher == null || matcher.matches(rel))
                    .sorted()
                    .toList();
            for (Path relative : candidates) {
                if (matches.size() >= maxResults) {
                    break;
                }
                scanFile(repoRoot, relative, query, maxResults, matches);
            }
        } catch (IOException e) {
            throw new ContractGuardException(new RunFailure(FailureCategory.SEARCH_FAILURE,
                    "repository search failed in " + repositoryId, false, null,
                    "Check filesystem permissions on the repository."), e);
        }
        return matches;
    }

    private void scanFile(Path repoRoot, Path relative, String query, int maxResults,
            List<SearchMatch> matches) throws IOException {
        Path file = repoRoot.resolve(relative);
        if (Files.size(file) > MAX_FILE_SIZE_BYTES || isBinary(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String relativeUnixPath = relative.toString().replace('\\', '/');
        for (int i = 0; i < lines.size() && matches.size() < maxResults; i++) {
            String line = lines.get(i);
            if (line.contains(query)) {
                String snippet = line.strip();
                if (snippet.length() > MAX_LINE_LENGTH) {
                    snippet = snippet.substring(0, MAX_LINE_LENGTH);
                }
                matches.add(new SearchMatch(relativeUnixPath, i + 1, snippet));
            }
        }
    }

    /** NUL byte in the first 4KB marks the file as binary. */
    static boolean isBinary(Path file) throws IOException {
        byte[] head;
        try (var in = Files.newInputStream(file)) {
            head = in.readNBytes(4096);
        }
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }
}
