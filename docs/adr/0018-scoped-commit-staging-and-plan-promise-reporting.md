# ADR-0018: Scoped commit staging, and reporting unfulfilled plan promises

- Status: accepted
- Date: 2026-07-24

## Context

Live use of the publish flow (FR-027) surfaced two related honesty gaps
between what ContractGuard *claims* it did and what actually landed in the
committed diff or the final report:

1. **`mvnw` showed up as changed in published PRs.** `MavenBuildValidationAdapter`/
   `DockerBuildValidationAdapter` flip the maven wrapper's executable bit
   on the real working-tree checkout via `MavenWrapperSupport.ensureExecutable`
   so `./mvnw verify` can even run — a legitimate, necessary mutation. But
   `GitCliAdapter.commit` (ADR-0007 point 6) staged with `git add -A`, so
   that incidental permission flip rode along into every commit alongside
   the actual approved patch, regardless of whether the plan authorized
   touching `mvnw` at all.
2. **A plan item can promise a file change that never happens, silently.**
   `MigrationPlan.approvedFiles()` authorizes `ImplementationAgent` to touch
   any file a plan item names in `expectedFiles`/`testsToUpdate`, but the
   agent is only ever forbidden from touching files *outside* that set —
   nothing requires it to touch every file *inside* it. A model can decide
   a listed file (e.g. `README.md`) needs no change and legitimately omit
   it, but the generated PR description and report still describe the plan
   item's original objective/`proposedAction` verbatim, which reads as a
   promise that was never checked against what the patch actually did.

## Decision

1. **`GitWorkspacePort.commit` takes an explicit `Set<String> paths`,
   never `git add -A`.** `GitCliAdapter.commit` now runs
   `git add -- <paths>` instead. `PublishService.commitBranch` computes
   `paths` as the union of `changedPaths()` across every `PatchArtifact`
   with `CheckStatus.APPLIED` on the run — the precise record of what
   ContractGuard actually wrote, already tracked per patch attempt, rather
   than the looser `approvedFiles()` set (which can include files a plan
   authorized but never touched).
2. **`ReportService` gains a plan-promise check, surfaced as a
   `Limitations` entry, not a blocker.** After execution (any patch with
   `CheckStatus.APPLIED`), each plan item's `expectedFiles` is compared
   against the union of applied patches' `changedPaths()`; any file listed
   but never touched adds "Plan item `<id>` (`<objective>`) expected
   changes to `<files>`, but the applied patch left `<untouched>`
   unmodified — review manually." This reuses the existing `limitations()`
   mechanism (already used for `UNKNOWN`/`POTENTIALLY_BREAKING` changes),
   so it appears in both the Markdown and JSON report with no new
   reporting concept, and requires no change to `ImplementationAgent`'s
   validation — an agent correctly omitting a truly-unnecessary file stays
   legitimate; the gap is only ever reported, never blocked.

## Consequences

- `GitWorkspacePort` is a narrow interface with exactly one production
  implementation and one caller (`PublishService`), so widening the
  `commit` signature was a contained, single-PR change (both test doubles
  updated alongside).
- The plan-promise check only ever adds a limitation; it cannot fail a run
  or change `RunState`, consistent with the project's existing "honest
  about gaps, not silently wrong" reporting philosophy.
- A plan item whose file was genuinely already correct (no change needed)
  still produces this limitation — a human reviewer, not the agent, is
  the one who confirms that judgment was right. Accepted as the simplest
  fix; a future slice could let the agent explicitly declare "no change
  needed" per file with a reason, suppressing the limitation when present.
