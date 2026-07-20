package com.contractguard.adapter.process;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.BuildValidationPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@code docker} CLI end to end: gated behind the
 * {@code docker} profile (see pom.xml) since it needs a local Docker daemon.
 * Uses the bundled demo consumer as a real Maven project to build.
 */
class DockerBuildValidationDockerTest {

    private static final Path CONSUMER_TEMPLATE = Path.of("../samples/customer-consumer");
    private static final String IMAGE = "eclipse-temurin:21-jdk";
    private static final Path HOST_M2 = Path.of(System.getProperty("user.home"), ".m2");

    @TempDir
    Path workspace;

    private WorkspacePolicy policy;

    @BeforeAll
    static void pullImageOnce() {
        // Keeps the timeout test's tight budget from being eaten by a first-time image pull.
        new ProcessRunner().run(List.of("docker", "pull", IMAGE), Path.of("."), Map.of(),
                Duration.ofMinutes(5), 100_000);
    }

    @BeforeEach
    void setUp() throws IOException {
        copyTree(CONSUMER_TEMPLATE, workspace.resolve("customer-consumer"));
        Files.createDirectories(workspace.resolve("customer-consumer/.git"));
        policy = new WorkspacePolicy(List.of(workspace));
    }

    @Test
    void sandboxedBuildSucceedsOfflineUsingTheHostMavenCache() {
        DockerBuildValidationAdapter adapter = new DockerBuildValidationAdapter(policy, new ProcessRunner(),
                Duration.ofMinutes(5), 2_000_000, IMAGE, "2g", "2", false, HOST_M2);

        BuildValidationPort.BuildResult result = adapter.run("customer-consumer", "maven-verify");

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("BUILD SUCCESS");
    }

    @Test
    void timeoutKillsTheContainerAndLeavesNoOrphanBehind() {
        DockerBuildValidationAdapter adapter = new DockerBuildValidationAdapter(policy, new ProcessRunner(),
                Duration.ofSeconds(2), 2_000_000, IMAGE, "2g", "2", false, HOST_M2);

        BuildValidationPort.BuildResult result = adapter.run("customer-consumer", "maven-verify");

        assertThat(result.timedOut()).isTrue();
        assertThat(runningValidationContainers()).isEmpty();
    }

    /** Bounded poll: `docker kill` already blocked until it returned, so removal should be near-instant. */
    private static List<String> runningValidationContainers() {
        ProcessRunner runner = new ProcessRunner();
        for (int attempt = 0; attempt < 5; attempt++) {
            ProcessRunner.ProcessResult result = runner.run(
                    List.of("docker", "ps", "-a", "--filter", "name=contractguard-validate-",
                            "--format", "{{.Names}}"),
                    Path.of("."), Map.of(), Duration.ofSeconds(10), 10_000);
            List<String> names = result.output().lines()
                    .map(String::strip).filter(line -> !line.isEmpty()).toList();
            if (names.isEmpty()) {
                return names;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return names;
            }
        }
        return runner.run(List.of("docker", "ps", "-a", "--filter", "name=contractguard-validate-",
                        "--format", "{{.Names}}"), Path.of("."), Map.of(), Duration.ofSeconds(10), 10_000)
                .output().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            paths.sorted(Comparator.naturalOrder()).forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
