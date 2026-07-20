package com.contractguard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A consumer repository hosted on GitHub and accessed over HTTPS (FR-027).
 * The credential itself is never carried on this object; it is looked up
 * separately, encrypted at rest (ADR-0007).
 */
public record RemoteRepository(
        String repositoryId, String cloneUrl, String owner, String name,
        String defaultBranch, Instant registeredAt) {

    private static final Pattern GITHUB_HTTPS_URL =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");

    public RemoteRepository {
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(cloneUrl, "cloneUrl");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        Objects.requireNonNull(registeredAt, "registeredAt");
    }

    /** Parses {@code owner}/{@code name} from a GitHub HTTPS clone URL (only host/transport supported, ADR-0007). */
    public static RemoteRepository forGitHub(String repositoryId, String cloneUrl, String defaultBranch,
            Instant registeredAt) {
        Matcher matcher = GITHUB_HTTPS_URL.matcher(cloneUrl.strip());
        if (!matcher.matches()) {
            throw ContractGuardException.of(FailureCategory.INVALID_REMOTE_URL,
                    "clone URL '%s' is not a supported GitHub HTTPS URL".formatted(cloneUrl),
                    "Use an https://github.com/{owner}/{repo} clone URL.");
        }
        return new RemoteRepository(repositoryId, cloneUrl.strip(), matcher.group(1), matcher.group(2),
                defaultBranch, registeredAt);
    }
}
