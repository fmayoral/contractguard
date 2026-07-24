package com.contractguard.application.service;

import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.testsupport.InMemorySpecSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecResolutionServiceTest {

    @TempDir
    Path specsDir;

    @TempDir
    Path uploadsDir;

    private SpecSourceService specSources;
    private SpecResolutionService resolution;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(specsDir.resolve("local.yaml"), "openapi: 3.0.0");
        Files.writeString(uploadsDir.resolve("uploaded.yaml"), "openapi: 3.0.0");

        Path sourceCache = specsDir.resolve("spec-source-cache");
        Files.createDirectories(sourceCache.resolve("acme-repo"));
        Files.writeString(sourceCache.resolve("acme-repo").resolve("remote.yaml"), "openapi: 3.0.0");
        specSources = new SpecSourceService(new InMemorySpecSourceRegistry(), (RemoteGitPort) null, sourceCache, Clock.systemUTC());

        resolution = new SpecResolutionService(specsDir, uploadsDir, specSources);
    }

    @Test
    void resolvesALocalPrefixedId() {
        assertThat(resolution.resolve("local:local.yaml")).isEqualTo(specsDir.resolve("local.yaml"));
    }

    @Test
    void resolvesABareNameAsLocalForBackwardCompatibility() {
        // The headless CLI/CI gate (FR-026) and every run persisted before spec sources/uploads
        // existed pass a bare file name -- must keep resolving against the local directory (ADR-0012).
        assertThat(resolution.resolve("local.yaml")).isEqualTo(specsDir.resolve("local.yaml"));
    }

    @Test
    void resolvesAnUploadPrefixedId() {
        assertThat(resolution.resolve("upload:uploaded.yaml")).isEqualTo(uploadsDir.resolve("uploaded.yaml"));
    }

    @Test
    void resolvesASourcePrefixedId() {
        Path resolved = resolution.resolve("source:acme-repo:remote.yaml");
        assertThat(resolved).isEqualTo(specsDir.resolve("spec-source-cache").resolve("acme-repo").resolve("remote.yaml"));
    }

    @Test
    void rejectsABlankId() {
        assertThatThrownBy(() -> resolution.resolve(" "))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_OPENAPI));
    }

    @Test
    void rejectsAMalformedSourceReference() {
        assertThatThrownBy(() -> resolution.resolve("source:missing-colon"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_OPENAPI));
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> resolution.resolve("local:../../etc/passwd"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_OPENAPI));
    }

    @Test
    void rejectsAFileThatDoesNotExist() {
        assertThatThrownBy(() -> resolution.resolve("local:ghost.yaml"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_OPENAPI));
    }
}
