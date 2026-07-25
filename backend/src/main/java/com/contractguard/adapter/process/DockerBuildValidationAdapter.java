package com.contractguard.adapter.process;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.BuildValidationPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Ids;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the consumer's build in a resource-limited, network-isolated
 * container instead of on the host (FR-030, ADR-0010). Opt-in: disabled by
 * default so the zero-setup local experience is unaffected.
 *
 * <p>Network egress is off by default, which would ordinarily break Maven
 * dependency resolution; instead of granting network access, the host's own
 * {@code ~/.m2} is bind-mounted read-only into the container, so previously
 * resolved dependencies (and the Maven wrapper's own cached distribution)
 * are available offline. A build whose dependencies were never resolved on
 * the host will still fail without network access — that's the honest
 * trade-off of "no egress by default", not a bug.
 */
public class DockerBuildValidationAdapter implements BuildValidationPort {

    private final WorkspacePolicy policy;
    private final ProcessRunner processRunner;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final String image;
    private final String memory;
    private final String cpus;
    private final boolean networkEnabled;
    private final Path mavenLocalRepo;

    // Explicit one-dependency-per-parameter constructor, consistent with this project's
    // hexagonal-architecture style throughout -- no bundling into a config/context object.
    @SuppressWarnings("java:S107")
    public DockerBuildValidationAdapter(WorkspacePolicy policy, ProcessRunner processRunner,
            Duration timeout, int maxOutputBytes, String image, String memory, String cpus,
            boolean networkEnabled, Path mavenLocalRepo) {
        this.policy = policy;
        this.processRunner = processRunner;
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.image = image;
        this.memory = memory;
        this.cpus = cpus;
        this.networkEnabled = networkEnabled;
        this.mavenLocalRepo = mavenLocalRepo;
    }

    @Override
    public BuildResult run(String repositoryId, String commandKey) {
        if (!"maven-verify".equals(commandKey)) {
            throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                    "validation command key '%s' is not allow-listed".formatted(commandKey),
                    "Only 'maven-verify' is allow-listed.");
        }
        Path repo = policy.resolveRepository(repositoryId);
        Path wrapper = repo.resolve("mvnw");
        if (!Files.exists(wrapper)) {
            throw ContractGuardException.of(FailureCategory.UNSUPPORTED_FEATURE,
                    "repository has no Maven wrapper: " + repo.getFileName(),
                    "Only Maven-wrapper builds are supported.");
        }
        // The bind mount below exposes this same file (and its permission bits) inside the
        // sandbox container, so it needs the executable bit here too, not just for the host path.
        MavenWrapperSupport.ensureExecutable(wrapper);
        String containerName = "contractguard-validate-" + Ids.shortId(Ids.newId());
        ProcessRunner.ProcessResult result = processRunner.run(
                dockerRunCommand(containerName, repo), repo, Map.of(), timeout, maxOutputBytes);
        if (result.timedOut()) {
            killOrphanedContainer(containerName, repo);
        }
        return new BuildResult(result.exitCode(), result.duration(), result.output(),
                result.truncated(), result.timedOut());
    }

    private List<String> dockerRunCommand(String containerName, Path repo) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--name", containerName,
                "--memory", memory, "--cpus", cpus, "--pids-limit", "512"));
        if (!networkEnabled) {
            command.add("--network");
            command.add("none");
        }
        command.add("-v");
        command.add(repo.toAbsolutePath() + ":/workspace:rw");
        if (mavenLocalRepo != null) {
            command.add("-v");
            command.add(mavenLocalRepo.toAbsolutePath() + ":/root/.m2:ro");
        }
        command.add("-w");
        command.add("/workspace");
        command.add(image);
        command.add("./mvnw");
        command.add("-B");
        command.add("-ntp");
        command.add("verify");
        return command;
    }

    /**
     * {@code --rm} only removes the container once it stops; forcibly killing the
     * {@code docker} CLI process on our side (what {@link ProcessRunner} does on
     * timeout) does not, by itself, stop the container running in the daemon.
     * {@code docker kill} stops it, and {@code --rm} then cleans it up — best-effort,
     * since the container may already have exited on its own by the time we get here.
     */
    private void killOrphanedContainer(String containerName, Path workingDirectory) {
        try {
            processRunner.run(List.of("docker", "kill", containerName), workingDirectory,
                    Map.of(), Duration.ofSeconds(10), 10_000);
        } catch (RuntimeException ignored) {
            // Best-effort cleanup; nothing else depends on this succeeding.
        }
    }
}
