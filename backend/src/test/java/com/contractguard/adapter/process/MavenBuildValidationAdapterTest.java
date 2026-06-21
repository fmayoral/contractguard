package com.contractguard.adapter.process;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenBuildValidationAdapterTest {

    @TempDir
    Path workspace;

    private MavenBuildValidationAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(workspace.resolve("no-wrapper/.git"));
        adapter = new MavenBuildValidationAdapter(new WorkspacePolicy(List.of(workspace)),
                new ProcessRunner(), Duration.ofMinutes(1), 100_000);
    }

    @Test
    void unknownCommandKeyIsAPolicyViolation() {
        assertThatThrownBy(() -> adapter.run("no-wrapper", "rm -rf /"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.POLICY_VIOLATION));
    }

    @Test
    void missingWrapperIsRejectedBeforeAnyExecution() {
        assertThatThrownBy(() -> adapter.run("no-wrapper", "maven-verify"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.UNSUPPORTED_FEATURE));
    }
}
