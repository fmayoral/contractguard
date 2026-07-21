package com.contractguard.adapter.git;

import com.contractguard.adapter.process.ProcessRunner;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RemoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Credentialed clone/fetch/push via the {@code git} CLI (ADR-0007, following
 * the fixed-argv precedent of ADR-0005). The token is never written to argv
 * or persisted remote config: only the username {@code x-access-token} is
 * embedded in the clone URL, and the password is supplied at run time by a
 * {@code GIT_ASKPASS} helper script that reads it from a subprocess-scoped
 * environment variable.
 */
public class RemoteGitCliAdapter implements RemoteGitPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteGitCliAdapter.class);
    private static final Duration REMOTE_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_OUTPUT = 200_000;
    private static final String TOKEN_ENV_VAR = "CONTRACTGUARD_GIT_TOKEN";
    private static final String USERNAME = "x-access-token";

    private final Path cacheRoot;
    private final ProcessRunner processRunner;
    private volatile Path askpassScript;

    public RemoteGitCliAdapter(Path cacheRoot, ProcessRunner processRunner) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.processRunner = processRunner;
    }

    @Override
    public void cloneOrRefresh(String repositoryId, RemoteRepository remote, String credential) {
        Path dest = destinationFor(repositoryId);
        if (Files.isDirectory(dest.resolve(".git"))) {
            run(dest, credential, "git", "fetch", "origin", remote.defaultBranch());
            run(dest, credential, "git", "checkout", remote.defaultBranch());
            run(dest, credential, "git", "reset", "--hard", "origin/" + remote.defaultBranch());
            run(dest, credential, "git", "clean", "-fd");
            return;
        }
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create remote cache directory " + cacheRoot, e);
        }
        run(cacheRoot, credential, "git", "clone", "--branch", remote.defaultBranch(),
                authenticatedUrl(remote.cloneUrl(), credential), dest.toAbsolutePath().toString());
    }

    @Override
    public void push(String repositoryId, String branchName, RemoteRepository remote, String credential) {
        Path dest = destinationFor(repositoryId);
        run(dest, credential, "git", "push", "-u", "origin", branchName);
    }

    @Override
    public void deleteLocalClone(String repositoryId) {
        Path dest = destinationFor(repositoryId);
        if (!dest.startsWith(cacheRoot) || !Files.isDirectory(dest)) {
            return;
        }
        try (var paths = Files.walk(dest)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                // Git marks loose object files read-only; on Windows (unlike POSIX, where a
                // writable parent directory is enough) that attribute blocks deletion outright.
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("could not fully delete local clone cache {}: {}", dest, e.getMessage());
        }
    }

    private Path destinationFor(String repositoryId) {
        return cacheRoot.resolve(repositoryId);
    }

    /**
     * Embeds the askpass username for HTTPS URLs so git prompts for a password
     * only when a credential was actually supplied (FR-043, ADR-0012): a blank
     * credential clones anonymously, exactly like a bare {@code git clone <url>}
     * against a public repository — embedding an empty password instead can make
     * GitHub reject even a public, unauthenticated-eligible request. Other
     * transports (used only by tests standing in a local bare repository for the
     * remote) pass through unchanged. Restricting which clone URLs are accepted
     * at all is the domain's job (only GitHub HTTPS URLs survive
     * {@link RemoteRepository#forGitHub}), not this adapter's.
     */
    private static String authenticatedUrl(String cloneUrl, String credential) {
        if (!cloneUrl.startsWith("https://") || credential == null || credential.isBlank()) {
            return cloneUrl;
        }
        return "https://" + USERNAME + "@" + cloneUrl.substring("https://".length());
    }

    private void run(Path workingDirectory, String credential, String... command) {
        Map<String, String> env = credential == null || credential.isBlank()
                ? Map.of("GIT_TERMINAL_PROMPT", "0")
                : Map.of(
                        "GIT_ASKPASS", askpassScript().toAbsolutePath().toString(),
                        "GIT_TERMINAL_PROMPT", "0",
                        TOKEN_ENV_VAR, credential);
        ProcessRunner.ProcessResult result =
                processRunner.run(List.of(command), workingDirectory, env, REMOTE_TIMEOUT, MAX_OUTPUT);
        if (result.exitCode() != 0) {
            // Callers that isolate per-repository failures (e.g. SpecSourceService) never see this
            // exception surface anywhere else, so it's logged here or it's lost entirely.
            log.warn("remote git command failed in {}: {} (exit {}): {}", workingDirectory,
                    String.join(" ", command), result.exitCode(), scrub(result.output()));
            throw ContractGuardException.of(FailureCategory.REMOTE_GIT_FAILURE,
                    "%s failed: %s".formatted(String.join(" ", command), scrub(result.output())),
                    "Check the clone URL, default branch and that the credential has the required scope.");
        }
    }

    /** The askpass helper itself never leaks the token, but strip it from any echoed git output. */
    private static String scrub(String output) {
        return output.strip();
    }

    /** Installed lazily on first use so unrelated app startups never touch the filesystem. */
    private Path askpassScript() {
        Path script = askpassScript;
        if (script == null) {
            script = installAskpassScript(cacheRoot);
            askpassScript = script;
        }
        return script;
    }

    private static Path installAskpassScript(Path directory) {
        try {
            Files.createDirectories(directory);
            Path script = directory.resolve("askpass.sh");
            // Built by concatenation, not String.formatted: the script needs exact LF line
            // endings (it runs under sh even on a Windows checkout via Git Bash), and %n would
            // substitute the platform line separator instead.
            String contents = "#!/bin/sh\nprintf '%s' \"$" + TOKEN_ENV_VAR + "\"\n";
            Files.writeString(script, contents);
            try {
                Files.setPosixFilePermissions(script,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows dev checkout); git for Windows can still
                // run the script via its bundled sh without the executable bit.
            }
            return script;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot install git askpass helper", e);
        }
    }
}
