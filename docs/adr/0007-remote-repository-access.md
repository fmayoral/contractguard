# ADR-0007: Remote repository access over HTTPS, GitHub-only, credentials encrypted at rest

- Status: accepted
- Date: 2026-07-20

## Context

FR-027 replaces the workspace-directory-only model: a consumer repository
today must already be a local checkout under a configured workspace root
(§17, `WorkspacePolicy`). To analyse and remediate a repository ContractGuard
does not already have on disk, it needs to clone it, and to publish a
validated remediation it needs to push a branch and open a pull request —
all without ever holding a credential in plaintext at rest.

The roadmap scopes this as GitHub-only, HTTPS-token-only for the first
slice (GitLab/Bitbucket and SSH are additive later behind the same ports).

## Decision

1. **Git via CLI, not JGit.** `RemoteGitCliAdapter` shells out to `git`
   with fixed argument arrays, exactly like `GitCliAdapter` (ADR-0005). No
   new git library; behaviour is identical to what the operator's own `git`
   would do, and the hexagonal architecture test already forbids JGit from
   the domain/application layers.
2. **Token never touches argv or persisted remote config.** Only the
   fixed, non-secret username `x-access-token` is embedded in the clone
   URL. The password is supplied at run time by a `GIT_ASKPASS` helper
   script that reads the token from a subprocess-scoped environment
   variable (`ProcessRunner`'s existing `extraEnvironment` parameter) —
   never from a command-line argument, so it cannot appear in `ps` output
   or shell history.
3. **Credentials encrypted at rest with AES-256-GCM.** `AesGcmCredentialCipher`
   encrypts the token before `JdbcRemoteRepositoryRegistry` writes it to the
   new `remote_repositories` table (Flyway `V2`, additive, both vendor
   folders). The master key comes from `CONTRACTGUARD_CREDENTIAL_KEY`
   (base64, 32 bytes), read the same way the LLM gateway already reads its
   API key — an env var referenced directly in `application.yml`. The
   cipher never fails at application startup, only when a credential is
   actually encrypted or decrypted, so local-only deployments need no new
   configuration.
4. **One credential per repository registration**, not a separate reusable
   credential store. The roadmap's "scoped credentials" language is
   satisfied by scoping to the repository; a shared-credential abstraction
   is unneeded speculative generality for this slice.
5. **The clone cache is a workspace root.** Cloned repositories land under
   `<storage.directory>/remote-cache/<repositoryId>` (mirroring
   `GitCliAdapter`'s existing `scratch` directory under the same storage
   root), which `ApplicationConfiguration` adds to `WorkspacePolicy`'s
   configured roots. Once cloned, a remote repository is indistinguishable
   from a local one to diff, search and execution — none of those adapters
   needed a remote-aware branch. `RunService.createRun` calls
   `RemoteRepositoryService.ensureLocalClone` (clone-or-refresh, a no-op for
   unregistered/local repositories) before the existing
   `WorkspacePolicy.resolveRepository` call.
6. **Commit is local-only; push is the only credentialed git operation.**
   `GitWorkspacePort` gains `commit(repositoryId, message)`, using a fixed
   `ContractGuard <contractguard@localhost>` bot identity — this resolves the
   roadmap's open question about push identity pragmatically: ContractGuard
   commits as itself, and pushes/PRs authenticate as whichever token the
   repository owner supplied at registration. Push and pull-request creation
   live on separate, explicitly credentialed ports (`RemoteGitPort`,
   `PullRequestPort`) so `GitWorkspacePort`'s existing "never touches a
   remote" invariant for local operations stays true.
7. **Publishing is a distinct, explicit human action.** `PublishService`
   mirrors the existing approval gate: a run must be `SUCCEEDED` (or
   `PUBLISH_FAILED`, retryable), and `POST /api/runs/{runId}/publish` is a
   separate call from `execute` — nothing pushes automatically. New
   `PUBLISHING`/`PUBLISHED`/`PUBLISH_FAILED` states extend `RunState`;
   `PUBLISHED` and `PUBLISH_FAILED` join the terminal set.
8. **GitHub only, no SDK.** `GitHubPullRequestAdapter` uses `java.net.http`
   exactly like `OpenAiCompatibleLlmGateway` (ADR-0003) to open a draft pull
   request. The PR body links evidence back to GitHub blob URLs in the
   *target* repository (`.../blob/<branch>/<path>#L<line>`) rather than to
   a ContractGuard report URL — the server binds to loopback only by
   design, so it has no public URL to link to; the GitHub link is more
   useful to a reviewer regardless.

## Consequences

- Adding GitLab/Bitbucket later means a new `PullRequestPort` adapter and
  (if SSH is added) a new credential shape — the port boundary already
  anticipates this, no rework of `PublishService` or the state machine.
- The retention policy (FR-023) purges finished runs by age including
  `SUCCEEDED`; a run left un-published past the retention window loses its
  working branch's local artifacts along with everything else. Acceptable
  for this slice; worth revisiting if publish latency becomes an issue.
- `GIT_ASKPASS` scripts are POSIX shell; deployment targets Linux
  containers only (already out of scope: Windows-service packaging), so
  this is not a new platform constraint, only relevant to local Windows
  development where Git for Windows' bundled `sh` still runs them.
- Two DDL copies (h2/postgresql) grow by one table, consistent with
  ADR-0006's accepted trade-off.
