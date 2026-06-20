package com.contractguard.adapter.artifacts;

import com.contractguard.application.port.ArtifactStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Run-scoped artifact directories under the storage root (§15). Artifact IDs
 * are sanitised file names; reads are confined to the run's own directory.
 */
public class FilesystemArtifactStore implements ArtifactStore {

    private final Path root;

    public FilesystemArtifactStore(Path storageDirectory) {
        this.root = storageDirectory.resolve("artifacts").toAbsolutePath().normalize();
    }

    @Override
    public String save(String runId, String name, String content) {
        String artifactId = sanitise(name);
        Path directory = runDirectory(runId);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(artifactId), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write artifact %s for run %s".formatted(name, runId), e);
        }
        return artifactId;
    }

    @Override
    public Optional<String> read(String runId, String artifactId) {
        Path file = runDirectory(runId).resolve(sanitise(artifactId)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read artifact %s of run %s".formatted(artifactId, runId), e);
        }
    }

    private Path runDirectory(String runId) {
        return root.resolve(sanitise(runId));
    }

    /** Collapses anything path-like into a flat, safe file name. */
    private static String sanitise(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
