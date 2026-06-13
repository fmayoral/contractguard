package com.contractguard.adapter.diff;

import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic OpenAPI diff adapter: parse, hash, compare (FR-003). */
public class SwaggerOpenApiDiffAdapter implements OpenApiDiffPort {

    private final OpenApiSpecReader reader = new OpenApiSpecReader();
    private final SpecDiffEngine engine = new SpecDiffEngine();

    @Override
    public DiffResult diff(Path oldSpec, Path newSpec) {
        SpecModel before = reader.read(oldSpec);
        SpecModel after = reader.read(newSpec);
        SpecDiffEngine.EngineResult result;
        try {
            result = engine.diff(before, after);
        } catch (RuntimeException e) {
            throw new ContractGuardException(new com.contractguard.domain.RunFailure(
                    FailureCategory.DIFF_FAILURE,
                    "diff engine failed: " + e.getMessage(), false, null,
                    "Inspect the specifications for unsupported constructs."), e);
        }
        return new DiffResult(result.changes(), result.warnings(), fileHash(oldSpec), fileHash(newSpec));
    }

    private static String fileHash(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot hash " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
