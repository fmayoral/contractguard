package com.contractguard.adapter.search;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RunFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads repository files under strict bounds (FR-007): workspace containment,
 * secret blocking, binary rejection, and a hard cap on returned characters.
 */
public class BoundedSourceReaderAdapter implements SourceReaderPort {

    private static final long MAX_FILE_SIZE_BYTES = 1_000_000;
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final WorkspacePolicy policy;

    public BoundedSourceReaderAdapter(WorkspacePolicy policy) {
        this.policy = policy;
    }

    @Override
    public FileContent read(String repositoryId, String relativePath, Integer startLine, Integer endLine) {
        policy.requireReadableFile(relativePath);
        Path repoRoot = policy.resolveRepository(repositoryId);
        Path file = policy.resolveFile(repoRoot, relativePath);
        if (!Files.isRegularFile(file)) {
            throw ContractGuardException.of(FailureCategory.SEARCH_FAILURE,
                    "file not found: " + relativePath,
                    "Only files reported by repository search can be read.");
        }
        try {
            if (Files.size(file) > MAX_FILE_SIZE_BYTES) {
                throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                        "file exceeds the size limit: " + relativePath,
                        "Large files are not exposed to the agent.");
            }
            if (FilesystemRepositorySearchAdapter.isBinary(file)) {
                throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                        "binary file rejected: " + relativePath,
                        "Binary files are never read or patched.");
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? lines.size() : Math.min(lines.size(), endLine);
            if (from > lines.size() || to < from) {
                throw ContractGuardException.of(FailureCategory.SEARCH_FAILURE,
                        "line range %s-%s outside file %s (%d lines)".formatted(
                                startLine, endLine, relativePath, lines.size()),
                        "Request a line range inside the file.");
            }
            StringBuilder content = new StringBuilder();
            boolean truncated = false;
            int lastIncluded = from - 1;
            for (int i = from - 1; i < to; i++) {
                String line = lines.get(i);
                if (content.length() + line.length() + 1 > MAX_OUTPUT_CHARS) {
                    truncated = true;
                    break;
                }
                if (content.length() > 0) {
                    content.append('\n');
                }
                content.append(line);
                lastIncluded = i + 1;
            }
            return new FileContent(relativePath.replace('\\', '/'), content.toString(),
                    from, lastIncluded, lines.size(), truncated);
        } catch (IOException e) {
            throw new ContractGuardException(new RunFailure(FailureCategory.SEARCH_FAILURE,
                    "cannot read " + relativePath, false, null,
                    "Check that the file is valid UTF-8 text."), e);
        }
    }
}
