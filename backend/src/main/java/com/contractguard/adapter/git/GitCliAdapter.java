package com.contractguard.adapter.git;

import com.contractguard.adapter.process.ProcessRunner;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.GitWorkspacePort;
import com.contractguard.application.port.PatchPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Git operations via the CLI with fixed argument arrays (ADR-0005). The diff
 * itself is computed with java-diff-utils from full file contents; git only
 * verifies ({@code apply --check}) and applies it — exact FR-013 semantics.
 */
public class GitCliAdapter implements GitWorkspacePort, PatchPort {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT = 200_000;
    private static final int CONTEXT_LINES = 3;
    private static final String BOT_NAME = "ContractGuard";
    private static final String BOT_EMAIL = "contractguard@localhost";

    private final WorkspacePolicy policy;
    private final ProcessRunner processRunner;
    private final Path scratchDirectory;

    public GitCliAdapter(WorkspacePolicy policy, ProcessRunner processRunner, Path scratchDirectory) {
        this.policy = policy;
        this.processRunner = processRunner;
        this.scratchDirectory = scratchDirectory;
    }

    @Override
    public GitStatus status(String repositoryId) {
        Path repo = policy.resolveRepository(repositoryId);
        String branch = git(repo, "rev-parse", "--abbrev-ref", "HEAD").strip();
        String porcelain = git(repo, "status", "--porcelain");
        List<String> dirty = porcelain.lines().filter(line -> !line.isBlank()).toList();
        return new GitStatus(branch, dirty.isEmpty(), dirty);
    }

    @Override
    public boolean branchExists(String repositoryId, String branchName) {
        Path repo = policy.resolveRepository(repositoryId);
        ProcessRunner.ProcessResult result = runGit(repo,
                List.of("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + branchName));
        return result.exitCode() == 0;
    }

    @Override
    public void createBranch(String repositoryId, String branchName) {
        Path repo = policy.resolveRepository(repositoryId);
        git(repo, "checkout", "-b", branchName);
    }

    @Override
    public void commit(String repositoryId, String message, Set<String> paths) {
        Path repo = policy.resolveRepository(repositoryId);
        List<String> addArgs = new ArrayList<>(List.of("add", "--"));
        addArgs.addAll(paths);
        git(repo, addArgs.toArray(new String[0]));
        git(repo, "-c", "user.name=" + BOT_NAME, "-c", "user.email=" + BOT_EMAIL, "commit", "-m", message);
    }

    @Override
    public String buildUnifiedDiff(String repositoryId, Map<String, String> newContents) {
        Path repo = policy.resolveRepository(repositoryId);
        StringBuilder diff = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(newContents).entrySet()) {
            String path = entry.getKey();
            policy.requireReadableFile(path);
            Path file = policy.resolveFile(repo, path);
            String rawContent = readContent(file, path);
            // Diff on normalised lines but emit hunks matching the file's own
            // line endings byte-for-byte: on Windows checkouts files are CRLF
            // and `git apply` compares exact bytes.
            boolean crlf = rawContent.contains("\r\n");
            List<String> oldLines = withTrailingNewline(rawContent.replace("\r\n", "\n"))
                    .lines().toList();
            List<String> newLines = withTrailingNewline(entry.getValue().replace("\r\n", "\n"))
                    .lines().toList();
            Patch<String> patch = DiffUtils.diff(oldLines, newLines);
            if (patch.getDeltas().isEmpty()) {
                continue;
            }
            List<String> hunk = UnifiedDiffUtils.generateUnifiedDiff(
                    "a/" + path, "b/" + path, oldLines, patch, CONTEXT_LINES);
            for (String line : hunk) {
                diff.append(line);
                if (crlf && isContentLine(line)) {
                    diff.append('\r');
                }
                diff.append('\n');
            }
        }
        return diff.toString();
    }

    /** Hunk content lines (context/added/removed) as opposed to headers. */
    private static boolean isContentLine(String line) {
        if (line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("@@")) {
            return false;
        }
        return line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '+' || line.charAt(0) == '-';
    }

    @Override
    public PatchCheck check(String repositoryId, String unifiedDiff) {
        Path repo = policy.resolveRepository(repositoryId);
        List<String> rejections = new ArrayList<>();
        if (unifiedDiff.isBlank()) {
            rejections.add("patch is empty");
            return new PatchCheck(false, List.of(), rejections, 0, 0);
        }
        if (unifiedDiff.contains("GIT binary patch")) {
            rejections.add("binary patches are rejected");
        }
        List<String> changedPaths = changedPaths(unifiedDiff);
        for (String path : changedPaths) {
            if (policy.isBlockedFile(path)) {
                rejections.add("patch touches blocked file " + path);
            }
        }
        Path patchFile = writeScratch(unifiedDiff);
        try {
            ProcessRunner.ProcessResult result = runGit(repo,
                    List.of("git", "-c", "core.autocrlf=false", "apply", "--check", "--verbose",
                            patchFile.toAbsolutePath().toString()));
            if (result.exitCode() != 0) {
                rejections.add("git apply --check failed: " + result.output().strip());
            }
        } finally {
            deleteQuietly(patchFile);
        }
        int added = countPrefixed(unifiedDiff, '+');
        int removed = countPrefixed(unifiedDiff, '-');
        return new PatchCheck(rejections.isEmpty(), changedPaths, rejections, added, removed);
    }

    @Override
    public void apply(String repositoryId, String unifiedDiff) {
        Path repo = policy.resolveRepository(repositoryId);
        Path patchFile = writeScratch(unifiedDiff);
        try {
            // autocrlf off: the patch was computed from exact on-disk bytes and
            // must be applied byte-faithfully regardless of user git settings.
            ProcessRunner.ProcessResult result = runGit(repo,
                    List.of("git", "-c", "core.autocrlf=false", "apply",
                            patchFile.toAbsolutePath().toString()));
            if (result.exitCode() != 0) {
                throw ContractGuardException.of(FailureCategory.PATCH_APPLICATION_FAILED,
                        "git apply failed: " + result.output().strip(),
                        "The working branch may be partially patched; discard it and re-run.");
            }
        } finally {
            deleteQuietly(patchFile);
        }
    }

    static List<String> changedPaths(String unifiedDiff) {
        List<String> paths = new ArrayList<>();
        unifiedDiff.lines()
                .filter(line -> line.startsWith("+++ b/"))
                .map(line -> line.substring("+++ b/".length()).strip())
                .forEach(paths::add);
        return paths;
    }

    private static int countPrefixed(String diff, char prefix) {
        return (int) diff.lines()
                .filter(line -> !line.isEmpty() && line.charAt(0) == prefix)
                .filter(line -> !line.startsWith("+++") && !line.startsWith("---"))
                .count();
    }

    private String git(Path repo, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.ProcessResult result = runGit(repo, command);
        if (result.exitCode() != 0) {
            throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                    "git %s failed: %s".formatted(args[0], result.output().strip()),
                    "Inspect the repository state; no destructive action was taken.");
        }
        return result.output();
    }

    private ProcessRunner.ProcessResult runGit(Path repo, List<String> command) {
        return processRunner.run(command, repo, Map.of(), GIT_TIMEOUT, MAX_OUTPUT);
    }

    private static String readContent(Path file, String relativePath) {
        if (!Files.isRegularFile(file)) {
            throw ContractGuardException.of(FailureCategory.PATCH_REJECTED,
                    "patched file does not exist: " + relativePath,
                    "Patches modify existing files only; file creation is not supported.");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + relativePath, e);
        }
    }

    private static String withTrailingNewline(String content) {
        return content.endsWith("\n") ? content : content + "\n";
    }

    private Path writeScratch(String unifiedDiff) {
        try {
            Files.createDirectories(scratchDirectory);
            Path file = Files.createTempFile(scratchDirectory, "patch-", ".diff");
            Files.writeString(file, unifiedDiff, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write patch scratch file", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // scratch cleanup only; nothing depends on it
        }
    }
}
