# ADR-0005: Git via CLI with fixed argument arrays; sample repo materialised into a workspace

- Status: accepted
- Date: 2026-06-06

## Context

FR-013 mandates `git apply --check` semantics; the safety rules forbid shell
strings built from model output. The bundled consumer must be a real Git
repository at demo time, but a nested `.git` directory cannot be committed
inside this repository.

## Decision

1. All Git and build operations run through a single process-execution
   adapter that maps approved command keys to fixed argument arrays
   (`git status --porcelain=v2`, `git rev-parse --abbrev-ref HEAD`,
   `git checkout -b <branch>`, `git apply --check <patch>`, `git apply
   <patch>`, `mvnw[.cmd] -B verify`), with timeouts and output caps. No
   string ever reaches a shell interpreter; model output is never an
   executable.
2. `samples/customer-consumer/` is committed as a plain source template.
   `scripts/reset-demo.(sh|ps1)` copies it into the git-ignored
   `workspace/` root and creates a fresh Git repository there with one
   initial commit. That copy is the repository ContractGuard registers and
   mutates (FR-022 demo reset = re-run the script).

## Consequences

- Exact `git apply --check` fidelity; no JGit behavioural differences.
- Requires `git` on PATH — already a stated prerequisite of the tool.
- Demo mutations are confined to `workspace/`, keeping this repository's
  history clean and making reset trivial.
