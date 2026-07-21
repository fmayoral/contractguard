package com.contractguard.application.service;

import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.RemoteRepository;
import com.contractguard.testsupport.InMemorySpecSourceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecSourceServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path cacheRoot;

    private final InMemorySpecSourceRegistry registry = new InMemorySpecSourceRegistry();

    @Test
    void registerParsesGitHubUrlAndAcceptsABlankToken() {
        SpecSourceService service = new SpecSourceService(registry, (RemoteGitPort) null, cacheRoot, CLOCK);

        RemoteRepository registered = service.register("openapi-specs",
                "https://github.com/acme/openapi-specs", "main", "");

        assertThat(registered.owner()).isEqualTo("acme");
        assertThat(registered.name()).isEqualTo("openapi-specs");
        assertThat(registry.credentialFor("openapi-specs")).isEmpty();
        assertThat(service.listRegistered()).containsExactly(registered);
    }

    @Test
    void registerRejectsAnInvalidRepositoryId() {
        SpecSourceService service = new SpecSourceService(registry, (RemoteGitPort) null, cacheRoot, CLOCK);

        assertThatThrownBy(() -> service.register("../escape", "https://github.com/acme/openapi-specs",
                "main", null))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void listSpecFilesClonesEachRegisteredSourceAndListsItsSpecFiles() throws IOException {
        List<String> cloned = new ArrayList<>();
        RemoteGitPort cloningGit = new RemoteGitPort() {
            @Override
            public void cloneOrRefresh(String repositoryId, RemoteRepository remote, String credential) {
                cloned.add(repositoryId);
                Path dir = cacheRoot.resolve(repositoryId);
                try {
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("openapi.yaml"), "openapi: 3.0.0");
                    Files.writeString(dir.resolve("README.md"), "not a spec");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }

            @Override
            public void push(String repositoryId, String branchName, RemoteRepository remote, String credential) {
                throw new UnsupportedOperationException("spec sources are never pushed to");
            }
        };
        SpecSourceService service = new SpecSourceService(registry, cloningGit, cacheRoot, CLOCK);
        service.register("openapi-specs", "https://github.com/acme/openapi-specs", "main", "tok");

        List<SpecSourceService.SpecSourceFile> files = service.listSpecFiles();

        assertThat(cloned).containsExactly("openapi-specs");
        assertThat(files).extracting(SpecSourceService.SpecSourceFile::fileName).containsExactly("openapi.yaml");
        assertThat(files).extracting(SpecSourceService.SpecSourceFile::sourceId).containsExactly("openapi-specs");
    }

    @Test
    void aSourceThatFailsToCloneIsSkippedRatherThanFailingTheWholeListing() {
        RemoteGitPort brokenGit = new RemoteGitPort() {
            @Override
            public void cloneOrRefresh(String repositoryId, RemoteRepository remote, String credential) {
                throw new RuntimeException("network unreachable");
            }

            @Override
            public void push(String repositoryId, String branchName, RemoteRepository remote, String credential) {
                throw new UnsupportedOperationException();
            }
        };
        SpecSourceService service = new SpecSourceService(registry, brokenGit, cacheRoot, CLOCK);
        service.register("broken-source", "https://github.com/acme/broken", "main", "tok");

        assertThat(service.listSpecFiles()).isEmpty();
    }

    @Test
    void resolveRejectsPathEscapeOutsideTheSourcesDirectory() throws IOException {
        Files.createDirectories(cacheRoot.resolve("openapi-specs"));
        Files.writeString(cacheRoot.resolve("openapi-specs/openapi.yaml"), "openapi: 3.0.0");
        // A decoy file one level above the source's own directory -- must never be reachable.
        Files.writeString(cacheRoot.resolve("secret.txt"), "not a spec file");
        SpecSourceService service = new SpecSourceService(registry, (RemoteGitPort) null, cacheRoot, CLOCK);

        assertThat(service.resolve("openapi-specs", "openapi.yaml")).isRegularFile();
        assertThatThrownBy(() -> service.resolve("openapi-specs", "../secret.txt"))
                .isInstanceOf(ContractGuardException.class);
    }
}
