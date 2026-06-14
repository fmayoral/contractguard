package com.contractguard.application.port;

/**
 * Bounded read access to repository files (FR-007). Implementations enforce
 * workspace containment, secret-file blocking, binary rejection and output
 * limits; callers can trust returned content to be safe to show or send
 * (after redaction) to the model.
 */
public interface SourceReaderPort {

    /**
     * @param startLine 1-based inclusive, null for file start
     * @param endLine   1-based inclusive, null for file end
     */
    FileContent read(String repositoryId, String relativePath, Integer startLine, Integer endLine);

    /**
     * @param content   requested lines joined with {@code \n}
     * @param truncated true when limits cut the returned content short
     */
    record FileContent(String relativePath, String content, int startLine, int endLine,
            int totalLines, boolean truncated) {
    }
}
