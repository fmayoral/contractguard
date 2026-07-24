package com.contractguard.adapter.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessRunnerTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final ProcessRunner runner = new ProcessRunner();
    private final Path cwd = Path.of(".");

    /** No `sleep` binary on Windows; `ping` is the standard portable stand-in for a timed delay. */
    private static List<String> sleepCommand(int seconds) {
        return WINDOWS
                ? List.of("ping", "-n", String.valueOf(seconds + 1), "127.0.0.1")
                : List.of("sleep", String.valueOf(seconds));
    }

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
    void timeoutKillsAStillRunningProcessInsteadOfWaitingForItToExit() {
        // A process that runs (and keeps producing output) well past the configured
        // timeout must be killed close to the deadline, not merely detected as having
        // overrun after it eventually finished on its own.
        Instant started = Instant.now();

        ProcessRunner.ProcessResult result = runner.run(
                sleepCommand(20), cwd, Map.of(), Duration.ofSeconds(2), 10_000);

        Duration wallClock = Duration.between(started, Instant.now());
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        // Generous upper bound: destroyForcibly + the 10s grace wait, well under the 20s sleep.
        assertThat(wallClock).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void unknownExecutableFailsFast() {
        List<String> command = List.of("definitely-not-a-real-binary-xyz");
        Map<String, String> noEnv = Map.of();
        Duration timeout = Duration.ofSeconds(5);
        assertThatThrownBy(() -> runner.run(command, cwd, noEnv, timeout, 1_000))
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
