package com.contractguard.adapter.search;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.SourceReaderPort.FileContent;
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

class BoundedSourceReaderAdapterTest {

    @TempDir
    Path workspace;

    private BoundedSourceReaderAdapter reader;

    @BeforeEach
    void setUp() throws IOException {
        Path repo = workspace.resolve("demo");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve("File.java"), "line1\nline2\nline3\nline4\nline5");
        Files.writeString(repo.resolve("secrets.properties"), "token=abc");
        Files.write(repo.resolve("blob.bin"), new byte[] {1, 0, 2, 0});
        reader = new BoundedSourceReaderAdapter(new WorkspacePolicy(List.of(workspace)));
    }

    @Test
    void readsRequestedLineRange() {
        FileContent content = reader.read("demo", "File.java", 2, 4);
        assertThat(content.content()).isEqualTo("line2\nline3\nline4");
        assertThat(content.startLine()).isEqualTo(2);
        assertThat(content.endLine()).isEqualTo(4);
        assertThat(content.totalLines()).isEqualTo(5);
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void readsWholeFileWhenNoRangeGiven() {
        FileContent content = reader.read("demo", "File.java", null, null);
        assertThat(content.content()).startsWith("line1").endsWith("line5");
    }

    @Test
    void rejectsBlockedFiles() {
        assertThatThrownBy(() -> reader.read("demo", "secrets.properties", null, null))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.POLICY_VIOLATION));
    }

    @Test
    void rejectsBinaryFiles() {
        assertThatThrownBy(() -> reader.read("demo", "blob.bin", null, null))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.POLICY_VIOLATION));
    }

    @Test
    void rejectsMissingFilesAndBadRanges() {
        assertThatThrownBy(() -> reader.read("demo", "nope.java", null, null))
                .isInstanceOf(ContractGuardException.class);
        assertThatThrownBy(() -> reader.read("demo", "File.java", 99, 120))
                .isInstanceOf(ContractGuardException.class);
        assertThatThrownBy(() -> reader.read("demo", "File.java", 4, 2))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void rejectsEscapePaths() {
        assertThatThrownBy(() -> reader.read("demo", "../other.txt", null, null))
                .isInstanceOf(ContractGuardException.class);
    }
}
