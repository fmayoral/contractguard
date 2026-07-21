package com.contractguard.adapter.git;

import com.contractguard.adapter.process.ProcessRunner;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.RemoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test against real local Git repositories: a bare repo stands
 * in for "the remote" so the clone/fetch/push plumbing is proven without any
 * network access or real GitHub credentials (mirrors {@link GitCliAdapterTest}).
 */
class RemoteGitCliAdapterTest {

    @TempDir
    Path bareRepoDir;

    @TempDir
    Path seedDir;

    @TempDir
    Path cacheDir;

    private final ProcessRunner runner = new ProcessRunner();
    private RemoteGitCliAdapter adapter;
    private RemoteRepository remote;

    @BeforeEach
    void setUp() {
        git(bareRepoDir, "init", "-q", "--bare");
        git(seedDir, "init", "-q", "-b", "main");
        write(seedDir.resolve("App.java"), "class App {\n    String fullName;\n}\n");
        git(seedDir, "add", "-A");
        git(seedDir, "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "commit", "-q", "-m", "initial");
        git(seedDir, "remote", "add", "origin", bareRepoDir.toString());
        git(seedDir, "push", "-q", "origin", "main");

        adapter = new RemoteGitCliAdapter(cacheDir, runner);
        remote = new RemoteRepository("customer-consumer", bareRepoDir.toString(), "acme", "widgets",
                "main", Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void clonesOnFirstUse() {
        adapter.cloneOrRefresh("customer-consumer", remote, "unused-token");

        Path checkout = cacheDir.resolve("customer-consumer");
        assertThat(checkout.resolve(".git")).isDirectory();
        assertThat(checkout.resolve("App.java")).hasContent("class App {\n    String fullName;\n}\n");
    }

    @Test
    void clonesWithoutACredentialForAnUnauthenticatedPublicRepository() {
        adapter.cloneOrRefresh("customer-consumer", remote, "");

        Path checkout = cacheDir.resolve("customer-consumer");
        assertThat(checkout.resolve(".git")).isDirectory();
        assertThat(checkout.resolve("App.java")).hasContent("class App {\n    String fullName;\n}\n");
    }

    @Test
    void refreshFetchesNewCommitsAndDiscardsLocalDrift() throws IOException {
        adapter.cloneOrRefresh("customer-consumer", remote, "unused-token");
        Path checkout = cacheDir.resolve("customer-consumer");

        // Local drift left behind by a previous run's execution phase.
        Files.writeString(checkout.resolve("App.java"), "class App {\n    String locallyEdited;\n}\n");
        Files.writeString(checkout.resolve("untracked.txt"), "leftover\n");

        // The provider pushed a new commit upstream in the meantime.
        write(seedDir.resolve("App.java"), "class App {\n    String remoteUpdate;\n}\n");
        git(seedDir, "add", "-A");
        git(seedDir, "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "commit", "-q", "-m", "remote update");
        git(seedDir, "push", "-q", "origin", "main");

        adapter.cloneOrRefresh("customer-consumer", remote, "unused-token");

        assertThat(checkout.resolve("App.java")).hasContent("class App {\n    String remoteUpdate;\n}\n");
        assertThat(checkout.resolve("untracked.txt")).doesNotExist();
    }

    @Test
    void pushesABranchToTheRemote() {
        adapter.cloneOrRefresh("customer-consumer", remote, "unused-token");
        Path checkout = cacheDir.resolve("customer-consumer");
        git(checkout, "checkout", "-q", "-b", "contractguard/run-abc1234");
        write(checkout.resolve("App.java"), "class App {\n    String displayName;\n}\n");
        git(checkout, "add", "-A");
        git(checkout, "-c", "user.name=ContractGuard", "-c", "user.email=contractguard@localhost",
                "commit", "-q", "-m", "remediate");

        adapter.push("customer-consumer", "contractguard/run-abc1234", remote, "unused-token");

        ProcessRunner.ProcessResult result = runner.run(
                List.of("git", "rev-parse", "--verify", "--quiet", "refs/heads/contractguard/run-abc1234"),
                bareRepoDir, Map.of(), Duration.ofSeconds(10), 10_000);
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void unreachableRemoteFailsTyped() {
        RemoteRepository broken = new RemoteRepository("customer-consumer",
                cacheDir.resolve("does-not-exist").toString(), "acme", "widgets", "main",
                Instant.parse("2026-07-01T00:00:00Z"));

        assertThatThrownBy(() -> adapter.cloneOrRefresh("customer-consumer", broken, "unused-token"))
                .isInstanceOf(ContractGuardException.class);
    }

    private void git(Path workingDirectory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.ProcessResult result =
                runner.run(command, workingDirectory, Map.of(), Duration.ofSeconds(30), 100_000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git setup failed: " + result.output());
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
