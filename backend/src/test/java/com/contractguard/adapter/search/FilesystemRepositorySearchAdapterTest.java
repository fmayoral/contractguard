package com.contractguard.adapter.search;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.RepositorySearchPort.SearchMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemRepositorySearchAdapterTest {

    @TempDir
    Path workspace;

    private FilesystemRepositorySearchAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        Path repo = workspace.resolve("demo");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/config"), "url = https://example.com/customers/");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Client.java"), """
                public class Client {
                    static final String PATH = "/customers/{id}";
                    String fullName;
                }
                """);
        Files.writeString(repo.resolve("README.md"), "Calls GET /customers/{id} upstream.");
        Files.writeString(repo.resolve("notes.txt"), "fullName appears here\nfullName again");
        Files.write(repo.resolve("logo.png"), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x00, 0x01});
        adapter = new FilesystemRepositorySearchAdapter(new WorkspacePolicy(List.of(workspace)));
    }

    @Test
    void findsMatchesWithExactLineNumbers() {
        List<SearchMatch> matches = adapter.search("demo", "/customers/{id}", null, 50);
        assertThat(matches).extracting(SearchMatch::relativePath, SearchMatch::lineNumber)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("README.md", 1),
                        org.assertj.core.groups.Tuple.tuple("src/Client.java", 2));
    }

    @Test
    void snippetContainsTheMatchedLine() {
        List<SearchMatch> matches = adapter.search("demo", "fullName", "src/**", 50);
        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.relativePath()).isEqualTo("src/Client.java");
            assertThat(match.lineText()).isEqualTo("String fullName;");
        });
    }

    @Test
    void gitInternalsAreNeverSearched() {
        List<SearchMatch> matches = adapter.search("demo", "example.com", null, 50);
        assertThat(matches).isEmpty();
    }

    @Test
    void binaryFilesAreSkipped() {
        List<SearchMatch> matches = adapter.search("demo", "PNG", null, 50);
        assertThat(matches).isEmpty();
    }

    @Test
    void resultLimitIsEnforced() {
        List<SearchMatch> matches = adapter.search("demo", "fullName", null, 2);
        assertThat(matches).hasSize(2);
    }

    @Test
    void blankQueryReturnsNothing() {
        assertThat(adapter.search("demo", " ", null, 10)).isEmpty();
    }

    @Test
    void vendoredBuildToolingAndOutputsAreNeverEvidence() throws IOException {
        Path repo = workspace.resolve("demo");
        // The real Maven wrapper contains $_.FullName in its PowerShell block.
        Files.writeString(repo.resolve("mvnw.cmd"), "$testPath = Join-Path $_.FullName \"bin\"");
        Files.createDirectories(repo.resolve(".mvn/wrapper"));
        Files.writeString(repo.resolve(".mvn/wrapper/notes.txt"), "fullName mention");
        Files.createDirectories(repo.resolve("target/classes"));
        Files.writeString(repo.resolve("target/classes/generated.txt"), "fullName in build output");

        assertThat(adapter.search("demo", "FullName", null, 50)).isEmpty();
        assertThat(adapter.search("demo", "fullName", "**", 50))
                .allSatisfy(match -> assertThat(match.relativePath())
                        .doesNotStartWith(".mvn").doesNotStartWith("target"));
    }
}
