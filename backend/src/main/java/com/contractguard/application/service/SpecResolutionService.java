package com.contractguard.application.service;

import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a qualified spec ID ({@code local:name}, {@code upload:name},
 * {@code source:sourceId:name}) to a file. A bare name with no recognised prefix is treated as
 * {@code local:} — the exact behaviour this had before spec sources and uploads existed, so the
 * headless CLI/CI gate (FR-026, which always passes bare file names) and every already-persisted
 * run keep working unchanged (ADR-0012).
 *
 * <p>Extracted from {@link RunService} so a second, unrelated consumer — {@link
 * SpecPreviewService} — can resolve spec IDs without depending on run creation at all;
 * {@code RunService} still owns {@link RunService#listSpecOptions()} (enumerating what's
 * available), which is a distinct concern from resolving one already-chosen ID.
 */
public class SpecResolutionService {

    private static final String CHOOSE_FROM_LISTING = "Choose a file from the specification listing.";

    private final Path specsDirectory;
    private final Path uploadedSpecsDirectory;
    private final SpecSourceService specSources;

    public SpecResolutionService(Path specsDirectory, Path uploadedSpecsDirectory, SpecSourceService specSources) {
        this.specsDirectory = specsDirectory;
        this.uploadedSpecsDirectory = uploadedSpecsDirectory;
        this.specSources = specSources;
    }

    public Path resolve(String specId) {
        if (specId == null || specId.isBlank()) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "invalid specification file name: " + specId,
                    CHOOSE_FROM_LISTING);
        }
        if (specId.startsWith("source:")) {
            String[] parts = specId.substring("source:".length()).split(":", 2);
            if (parts.length != 2) {
                throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                        "malformed spec source reference: " + specId,
                        CHOOSE_FROM_LISTING);
            }
            return specSources.resolve(parts[0], parts[1]);
        }
        if (specId.startsWith("upload:")) {
            return resolveInDirectory(uploadedSpecsDirectory, specId.substring("upload:".length()));
        }
        String fileName = specId.startsWith("local:") ? specId.substring("local:".length()) : specId;
        return resolveInDirectory(specsDirectory, fileName);
    }

    private static Path resolveInDirectory(Path directory, String fileName) {
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "invalid specification file name: " + fileName,
                    CHOOSE_FROM_LISTING);
        }
        Path resolved = directory.resolve(fileName).normalize();
        if (!resolved.startsWith(directory) || !Files.isRegularFile(resolved)) {
            throw ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                    "specification file not found: " + fileName,
                    CHOOSE_FROM_LISTING);
        }
        return resolved;
    }
}
