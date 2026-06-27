package com.contractguard.adapter.process;

import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.BuildValidationPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The only build validation the MVP supports: the repository's own Maven
 * wrapper with a fixed argument array (FR-016). The command allow-list is
 * this map — an unknown key is a typed policy violation, never an execution.
 */
public class MavenBuildValidationAdapter implements BuildValidationPort {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final WorkspacePolicy policy;
    private final ProcessRunner processRunner;
    private final Duration timeout;
    private final int maxOutputBytes;

    public MavenBuildValidationAdapter(WorkspacePolicy policy, ProcessRunner processRunner,
            Duration timeout, int maxOutputBytes) {
        this.policy = policy;
        this.processRunner = processRunner;
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public BuildResult run(String repositoryId, String commandKey) {
        Path repo = policy.resolveRepository(repositoryId);
        List<String> command = commandFor(commandKey, repo);
        // The consumer must build with the same JDK the backend runs on (Java 21).
        Map<String, String> environment = Map.of("JAVA_HOME", System.getProperty("java.home"));
        ProcessRunner.ProcessResult result =
                processRunner.run(command, repo, environment, timeout, maxOutputBytes);
        return new BuildResult(result.exitCode(), result.duration(), result.output(),
                result.truncated(), result.timedOut());
    }

    private List<String> commandFor(String commandKey, Path repo) {
        if (!"maven-verify".equals(commandKey)) {
            throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                    "validation command key '%s' is not allow-listed".formatted(commandKey),
                    "Only 'maven-verify' is supported in the MVP.");
        }
        if (WINDOWS) {
            Path wrapper = repo.resolve("mvnw.cmd");
            if (!Files.exists(wrapper)) {
                throw missingWrapper(repo);
            }
            return List.of("cmd.exe", "/c", wrapper.toAbsolutePath().toString(), "-B", "-ntp", "verify");
        }
        Path wrapper = repo.resolve("mvnw");
        if (!Files.exists(wrapper)) {
            throw missingWrapper(repo);
        }
        return List.of(wrapper.toAbsolutePath().toString(), "-B", "-ntp", "verify");
    }

    private static ContractGuardException missingWrapper(Path repo) {
        return ContractGuardException.of(FailureCategory.UNSUPPORTED_FEATURE,
                "repository has no Maven wrapper: " + repo.getFileName(),
                "Only Maven-wrapper builds are supported in the MVP.");
    }
}
