package com.contractguard.adapter.process;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard-clause coverage that needs no Docker daemon; the real sandboxed-run
 * behaviour is covered separately in {@code DockerBuildValidationDockerTest}
 * (gated behind the {@code docker} profile).
 */
class DockerBuildValidationAdapterTest {

    @TempDir
    Path workspace;

    private DockerBuildValidationAdapter adapter(Path root) {
        WorkspacePolicy policy = new WorkspacePolicy(List.of(root));
        return new DockerBuildValidationAdapter(policy, new ProcessRunner(), Duration.ofMinutes(5),
                1_000_000, "eclipse-temurin:21-jdk", "2g", "2", false, null);
    }

    @Test
    void unknownCommandKeyIsRejectedWithoutTouchingDocker() throws IOException {
        Files.createDirectories(workspace.resolve("demo/.git"));

        assertThatThrownBy(() -> adapter(workspace).run("demo", "not-allow-listed"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.POLICY_VIOLATION));
    }

    @Test
    void missingWrapperIsRejectedWithoutTouchingDocker() throws IOException {
        Files.createDirectories(workspace.resolve("demo/.git"));

        assertThatThrownBy(() -> adapter(workspace).run("demo", "maven-verify"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.UNSUPPORTED_FEATURE));
    }
}
