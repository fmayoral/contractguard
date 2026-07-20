# ADR-0010: Opt-in sandboxed validation, offline via a read-only `.m2` mount

- Status: accepted
- Date: 2026-07-21

## Context

FR-030 asks for consumer builds to run "in a resource-limited container
(CPU, memory, wall-clock, no network egress by default)" instead of on the
host. Today's `MavenBuildValidationAdapter` runs the consumer's own
`mvnw verify` directly on the machine running ContractGuard — arbitrary
third-party test code, from a repository ContractGuard doesn't control,
with the same privileges as the tool itself. Every other mutation path in
this project is already contained (workspace roots, fixed argv, secret
redaction, bounded repair); this was the one execution path that wasn't.

## Decisions

### 1. Opt-in, not a replacement

`contractguard.validation.docker.enabled` defaults to `false`.
`ApplicationConfiguration` picks `MavenBuildValidationAdapter` (host) or the
new `DockerBuildValidationAdapter` (sandboxed) behind the unchanged
`BuildValidationPort`, the same swap-an-adapter-behind-a-port pattern
already used for the LLM provider (mock vs. openai). This keeps the
zero-setup local demo experience exactly as it is today for anyone without
Docker installed; the safety upgrade is something an operator turns on.

### 2. "No network egress by default" is honored via an offline `.m2` mount, not a hole in the isolation

Blocking network access (`--network none`) and still expecting Maven to
resolve dependencies are in tension — most real builds need the network the
first time they see a dependency. Rather than open an exception for package
registries (which would undermine the point of isolating the build), the
host's own `~/.m2` is bind-mounted **read-only** into the container. This
covers both the resolved-dependency cache (`~/.m2/repository`) and the
Maven wrapper's own cached distribution (`~/.m2/wrapper`), so a build whose
dependencies have already been resolved on the host runs fully offline
inside the sandbox. A repository whose dependencies were never resolved on
the host will still fail without network access — that is the honest
consequence of the default, not a bug, and `network-enabled: true` remains
available for repositories that need it. Verified for real against the
bundled demo consumer, not just asserted: `DockerBuildValidationDockerTest`
runs an actual `docker run --network none` build using this mount and
checks for `BUILD SUCCESS`.

### 3. Fixed argv via the `docker` CLI through the existing `ProcessRunner`, not a new SDK dependency

Consistent with ADR-0005 (git) and ADR-0007 (remote git): `docker run` is
invoked as a fixed argument array through the same `ProcessRunner` every
other process-spawning adapter already uses, rather than adding
`docker-java` or an equivalent client library. Resource limits are plain
flags: `--memory`, `--cpus`, `--pids-limit 512` (fork-bomb defense, not
independently configurable — the FR only calls out CPU/memory/network/wall-
clock as tunable), `--rm --name contractguard-validate-<id>`.

### 4. Named containers so a killed build can't leave an orphan running

`--rm` only removes a container once it *stops*; forcibly killing the
`docker` CLI process on our side (what `ProcessRunner` does on timeout)
does not, by itself, stop the container in the daemon — that would leave an
orphaned container silently consuming resources after every timed-out
sandboxed build. Each run gets a unique `--name`, and on timeout
`DockerBuildValidationAdapter` issues an explicit best-effort
`docker kill <name>` before returning, which `--rm` then cleans up.
`DockerBuildValidationDockerTest` proves this by asserting
`docker ps -a --filter name=contractguard-validate-` is empty after a
timeout, not just that the method returned.

## A pre-existing bug this surfaced, fixed alongside it

Writing the timeout test above (`docker run` under a 2-second timeout
against a build that takes tens of seconds) exposed that `ProcessRunner`'s
timeout was not actually a wall-clock deadline: it read the child's output
to completion (or the byte cap) *before* ever checking `process.waitFor
(timeout)` — so the check only ever ran once the process had already
exited on its own, making the configured timeout a no-op for any process
that produced output steadily and eventually finished. `ProcessRunner` now
drains output on a background thread while `waitFor(timeout)` runs
concurrently on the caller's thread, so a process that overruns is killed
close to the deadline regardless of what it's still writing. This affects
every existing caller (`GitCliAdapter`, `RemoteGitCliAdapter`,
`MavenBuildValidationAdapter`), not just the new sandboxed adapter — all of
their tests still pass unchanged, and a new
`ProcessRunnerTest.timeoutKillsAStillRunningProcessInsteadOfWaitingForItToExit`
guards the fix directly. Fixed here rather than filed away, since
`ProcessRunner`'s whole reason to exist is bounding execution time (§17.6-8)
and the bug meant that guarantee didn't actually hold for any long-running
command — silently working around it in the new test would have shipped a
sandboxing feature on top of a timeout that still didn't work.

## Consequences

- First use of a given image pays a `docker pull` if it isn't already
  local; that time counts against the configured timeout. Documented in
  the README rather than special-cased in code.
- `docker-daemon-unreachable` failures currently surface as a generic
  non-zero exit with the raw Docker CLI error in the captured output/log
  artifact, not a dedicated failure category — acceptable for this slice,
  revisit if it proves confusing in practice.
- No `--user` mapping: build output inside the container may be root-owned
  on a Linux host. Out of scope here; the project's own deployment target
  is Linux containers, where this is a solvable but separate concern from
  sandboxing the validation step itself.
