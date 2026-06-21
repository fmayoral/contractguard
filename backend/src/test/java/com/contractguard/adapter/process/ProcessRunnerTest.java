package com.contractguard.adapter.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();
    private final Path cwd = Path.of(".");

    @Test
    void capturesExitCodeAndOutput() {
        ProcessRunner.ProcessResult result = runner.run(
                List.of("git", "--version"), cwd, Map.of(), Duration.ofSeconds(20), 10_000);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("git version");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.truncated()).isFalse();
        assertThat(result.duration()).isPositive();
    }

    @Test
    void nonZeroExitCodesAreReportedNotThrown() {
        ProcessRunner.ProcessResult result = runner.run(
                List.of("git", "rev-parse", "--verify", "definitely-not-a-ref"),
                cwd, Map.of(), Duration.ofSeconds(20), 10_000);
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void outputIsCappedAtTheLimit() {
        // `git help -a` prints well over 60 bytes.
        ProcessRunner.ProcessResult result = runner.run(
                List.of("git", "help", "-a"), cwd, Map.of(), Duration.ofSeconds(20), 60);
        assertThat(result.truncated()).isTrue();
        assertThat(result.output().getBytes()).hasSizeLessThanOrEqualTo(60);
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void unknownExecutableFailsFast() {
        assertThatThrownBy(() -> runner.run(
                List.of("definitely-not-a-real-binary-xyz"), cwd, Map.of(),
                Duration.ofSeconds(5), 1_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void environmentVariablesArePassedToTheChild() {
        ProcessRunner.ProcessResult result = runner.run(
                List.of("git", "var", "GIT_AUTHOR_IDENT"), cwd,
                Map.of("GIT_AUTHOR_NAME", "EnvProbe", "GIT_AUTHOR_EMAIL", "probe@example.com",
                        "GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z"),
                Duration.ofSeconds(20), 10_000);
        assertThat(result.output()).contains("EnvProbe");
    }
}
