package com.contractguard.adapter.git;

import com.contractguard.adapter.process.ProcessRunner;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.PatchPort;
import com.contractguard.domain.ContractGuardException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration test against a real Git repository created in a temp workspace. */
class GitCliAdapterTest {

    @TempDir
    Path workspace;

    @TempDir
    Path scratch;

    private GitCliAdapter adapter;
    private Path repo;
    private final ProcessRunner runner = new ProcessRunner();

    @BeforeEach
    void setUp() throws IOException {
        repo = workspace.resolve("demo");
        Files.createDirectories(repo);
        git("init", "-q", "-b", "main");
        Files.writeString(repo.resolve("App.java"), "class App {\n    String fullName;\n}\n");
        Files.writeString(repo.resolve("README.md"), "Calls /customers/{id}\n");
        git("add", "-A");
        git("-c", "user.name=Test", "-c", "user.email=test@example.com",
                "commit", "-q", "-m", "initial");
        adapter = new GitCliAdapter(new WorkspacePolicy(List.of(workspace)), runner, scratch);
    }

    private void git(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.ProcessResult result =
                runner.run(command, repo, Map.of(), Duration.ofSeconds(30), 100_000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git setup failed: " + result.output());
        }
    }

    @Test
    void statusReportsCleanRepositoryAndBranch() {
        GitWorkspacePort.GitStatus status = adapter.status("demo");
        assertThat(status.currentBranch()).isEqualTo("main");
        assertThat(status.clean()).isTrue();
        assertThat(status.dirtyEntries()).isEmpty();
    }

    @Test
    void statusReportsDirtyRepository() throws IOException {
        Files.writeString(repo.resolve("App.java"), "class App { /* modified */ }\n");
        Files.writeString(repo.resolve("untracked.txt"), "new\n");

        GitWorkspacePort.GitStatus status = adapter.status("demo");

        assertThat(status.clean()).isFalse();
        assertThat(status.dirtyEntries()).hasSize(2);
    }

    @Test
    void createsWorkBranchAndDetectsConflicts() {
        assertThat(adapter.branchExists("demo", "contractguard/run-abc")).isFalse();
        adapter.createBranch("demo", "contractguard/run-abc");
        assertThat(adapter.status("demo").currentBranch()).isEqualTo("contractguard/run-abc");
        assertThat(adapter.branchExists("demo", "contractguard/run-abc")).isTrue();
    }

    @Test
    void buildsChecksAndAppliesAMinimalPatch() throws IOException {
        String newContent = "class App {\n    String displayName;\n}\n";
        String diff = adapter.buildUnifiedDiff("demo", Map.of("App.java", newContent));

        assertThat(diff).contains("--- a/App.java").contains("+++ b/App.java")
                .contains("-    String fullName;").contains("+    String displayName;");

        PatchPort.PatchCheck check = adapter.check("demo", diff);
        assertThat(check.valid()).as(String.join("; ", check.rejections())).isTrue();
        assertThat(check.changedPaths()).containsExactly("App.java");
        assertThat(check.addedLines()).isEqualTo(1);
        assertThat(check.removedLines()).isEqualTo(1);

        adapter.apply("demo", diff);
        assertThat(Files.readString(repo.resolve("App.java"))).isEqualTo(newContent);
        // Applied but never committed: the working tree is dirty, no commits were made.
        assertThat(adapter.status("demo").clean()).isFalse();
    }

    @Test
    void unchangedContentProducesNoDiff() throws IOException {
        String current = Files.readString(repo.resolve("App.java"));
        assertThat(adapter.buildUnifiedDiff("demo", Map.of("App.java", current))).isBlank();
    }

    @Test
    void crlfFilesArePatchedByteFaithfully() throws IOException {
        // Windows checkouts materialise CRLF; the emitted hunks must match.
        Files.writeString(repo.resolve("Crlf.java"),
                "class Crlf {\r\n    String fullName;\r\n}\r\n");
        git("add", "-A");
        git("-c", "user.name=Test", "-c", "user.email=test@example.com",
                "commit", "-q", "-m", "crlf file");

        String diff = adapter.buildUnifiedDiff("demo",
                Map.of("Crlf.java", "class Crlf {\n    String displayName;\n}\n"));

        PatchPort.PatchCheck check = adapter.check("demo", diff);
        assertThat(check.valid()).as(String.join("; ", check.rejections())).isTrue();
        adapter.apply("demo", diff);
        assertThat(Files.readString(repo.resolve("Crlf.java")))
                .isEqualTo("class Crlf {\r\n    String displayName;\r\n}\r\n");
    }

    @Test
    void staleOrCorruptPatchFailsTheCheck() {
        String bogus = """
                --- a/App.java
                +++ b/App.java
                @@ -1,3 +1,3 @@
                 class App {
                -    String somethingThatIsNotThere;
                +    String displayName;
                 }
                """;
        PatchPort.PatchCheck check = adapter.check("demo", bogus);
        assertThat(check.valid()).isFalse();
        assertThat(check.rejections()).anySatisfy(r -> assertThat(r).contains("git apply --check failed"));
    }

    @Test
    void emptyAndBinaryAndBlockedPatchesAreRejected() {
        assertThat(adapter.check("demo", "").valid()).isFalse();
        assertThat(adapter.check("demo", "GIT binary patch\nliteral 5\n").valid()).isFalse();

        String blockedDiff = """
                --- a/secrets.properties
                +++ b/secrets.properties
                @@ -1 +1 @@
                -a
                +b
                """;
        PatchPort.PatchCheck check = adapter.check("demo", blockedDiff);
        assertThat(check.rejections()).anySatisfy(r -> assertThat(r).contains("blocked file"));
    }

    @Test
    void patchingAMissingFileFailsTyped() {
        Map<String, String> ghostFile = Map.of("Ghost.java", "x\n");
        assertThatThrownBy(() -> adapter.buildUnifiedDiff("demo", ghostFile))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void applyingACorruptPatchFailsTyped() {
        String bogus = """
                --- a/App.java
                +++ b/App.java
                @@ -1,3 +1,3 @@
                 class App {
                -    String missing;
                +    String displayName;
                 }
                """;
        assertThatThrownBy(() -> adapter.apply("demo", bogus))
                .isInstanceOf(ContractGuardException.class);
    }
}
