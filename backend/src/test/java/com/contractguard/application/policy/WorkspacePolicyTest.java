package com.contractguard.application.policy;

import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspacePolicyTest {

    @TempDir
    Path workspace;

    @TempDir
    Path elsewhere;

    private WorkspacePolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(workspace.resolve("repo-a/.git"));
        Files.createDirectories(workspace.resolve("repo-b/.git"));
        Files.createDirectories(workspace.resolve("not-a-repo"));
        policy = new WorkspacePolicy(List.of(workspace));
    }

    @Test
    void listsOnlyGitRepositoriesDirectlyUnderRoots() {
        assertThat(policy.listRepositories()).containsExactly("repo-a", "repo-b");
    }

    @Test
    void resolvesRegisteredRepository() {
        assertThat(policy.resolveRepository("repo-a")).exists();
    }

    @Test
    void rejectsUnknownRepository() {
        assertThatThrownBy(() -> policy.resolveRepository("ghost"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.REPOSITORY_OUTSIDE_WORKSPACE));
    }

    @Test
    void rejectsRepositoryIdsWithPathTricks() {
        for (String evil : new String[] {"../repo-a", "a/b", "a\\b", ".."}) {
            assertThatThrownBy(() -> policy.resolveRepository(evil))
                    .as("id %s", evil)
                    .isInstanceOf(ContractGuardException.class);
        }
    }

    @Test
    void rejectsTraversalOutOfRepository() {
        Path repo = policy.resolveRepository("repo-a");
        assertThatThrownBy(() -> policy.resolveFile(repo, "../repo-b/x.txt"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.POLICY_VIOLATION));
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path repo = policy.resolveRepository("repo-a");
        Path outsideTarget = Files.writeString(elsewhere.resolve("outside.txt"), "top secret");
        Path link = repo.resolve("innocent.txt");
        try {
            Files.createSymbolicLink(link, outsideTarget);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows without developer mode cannot create symlinks; covered in CI on Linux.
            assumeTrue(false, "symlinks not supported on this host");
        }
        assertThatThrownBy(() -> policy.resolveFile(repo, "innocent.txt"))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void resolvesRegularFileInsideRepository() throws IOException {
        Path repo = policy.resolveRepository("repo-a");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/App.java"), "class App {}");
        assertThat(policy.resolveFile(repo, "src/App.java")).exists();
    }

    @Test
    void blocksLikelySecretFiles() {
        for (String blocked : new String[] {
                ".env", ".env.local", "config/secrets.yaml", "deploy/credentials.json",
                "keys/server.pem", "certs/tls.key", "store.p12", "trust.jks",
                ".netrc", ".npmrc", "id_rsa", "id_rsa.pub", ".git/config", "a/.git/HEAD"}) {
            assertThat(policy.isBlockedFile(blocked)).as(blocked).isTrue();
        }
        assertThatThrownBy(() -> policy.requireReadableFile(".env"))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void allowsRegularSourceFiles() {
        for (String allowed : new String[] {
                "src/main/java/App.java", "pom.xml", "README.md", "application.properties",
                "src/Keyboard.java", "monkey.txt"}) {
            assertThat(policy.isBlockedFile(allowed)).as(allowed).isFalse();
        }
    }

    @Test
    void requiresAtLeastOneRoot() {
        List<Path> noRoots = List.of();
        assertThatThrownBy(() -> new WorkspacePolicy(noRoots))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
