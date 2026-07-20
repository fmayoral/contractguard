# ADR-0009: Bounded run queue, a real per-repository lock, and resume scoped to CREATED only

- Status: accepted
- Date: 2026-07-20

## Context

FR-032 asks for two things: "a bounded run queue with per-repository mutual
exclusion (generalising today's `REPOSITORY_BUSY` check)" and "runs
interrupted by a restart resume from the last persisted state or finalise as
FAILED — never a stuck non-terminal state." The second half looks simple
until you look at what "resume" would have to mean against this codebase's
state machine.

## Decisions

### 1. The existing repository-busy check has a real race, now closed

`RunService.createRun` checked `runs.findActiveByRepository(repositoryId)`
and then inserted a new row — classic check-then-act. Two simultaneous
requests for the same repository could both pass the check before either
row existed. `RepositoryLock` (`application.policy`) is an in-process
`ConcurrentHashMap`-backed mutex that closes exactly that window: acquired
before the check, released in a `finally` right after the row is written.
It does **not** need to stay held for the run's whole lifetime — once the
row exists, `findActiveByRepository` already correctly represents "busy" for
as long as the run stays non-terminal. An in-process lock is sufficient
because ContractGuard is single-instance by design (no distributed
deployment story exists); a multi-instance future would need a DB-level
lock instead.

### 2. The run queue is now an explicit, visible bound, not an implicit thread-pool detail

`RunRepository.countActive()` (a `COUNT(*) ... WHERE state NOT IN (...)`)
backs a configurable `contractguard.concurrency.max-active-runs` (default
10, `0` = unbounded). Exceeding it rejects creation with a new
`RUN_QUEUE_FULL` category (mapped to HTTP 503 — "retry later", the same
semantics as `LLM_UNAVAILABLE`). This replaces relying on the fixed
thread pool's internal unbounded queue as the only capacity signal, which
gave no feedback to the caller and no configurability.

### 3. Resume is scoped to `CREATED` only — deliberately, not by oversight

`RunState`'s transitions are one-shot by design: `AnalysisRun.transitionTo`
rejects anything not in the current state's `successors()`, and no state
lists itself as a valid target. That's not an accident to work around — it's
the exact mechanism that makes "no illegal transition is representable"
true elsewhere in the codebase. True resume (picking a run back up exactly
where a mid-flight branch creation, patch application, or publish left off)
would require either backward transitions or step-level re-entry logic that
tolerates a state re-firing into itself — both weaken that guarantee for a
feature whose entire value is "trust the state machine."

So: only `CREATED` is resumed, and it doesn't need any of that. A run in
`CREATED` has touched nothing — no branch, no patch, no push — so
re-dispatching `pipeline.analyse(...)` on restart is exactly the same
operation `createRun` already performs, just replayed. To make that
possible, `AnalysisRun` now persists `oldSpecFile`/`newSpecFile` (the
*requested* file names) immediately at construction — additively, alongside
the existing `oldSpecName`/`newSpecName`/hashes that are still only
recorded once diffing actually runs. `RunDocument.CURRENT_VERSION` moves to
3 for this.

Every other non-terminal state keeps today's behaviour exactly: finalised
as `FAILED` with a clear, honest remediation message. This is the FR's own
explicit fallback ("... or finalise as FAILED"), not a gap.

### 4. `AWAITING_APPROVAL` runs are no longer killed on restart

Separately from resume: `failInterruptedRuns()` used to mark *every*
non-terminal run FAILED on restart, including runs sitting in
`AWAITING_APPROVAL`. That's wrong — nothing is in flight for a run waiting
on a human decision; a restart doesn't orphan anything. These are now left
untouched.

## Consequences

- A future FR-024 (auth) doesn't change any of this — the lock and queue
  bound are unaffected by who is calling.
- If genuine mid-pipeline resume is ever wanted, it requires a deliberate,
  separate design decision about weakening the transition guard — not a
  quiet extension of this change. Flagging that tradeoff explicitly here so
  it isn't rediscovered the hard way.
- `RunService.failInterruptedRuns()` now returns `InterruptedRunRecovery
  (resumed, failed)` instead of a bare failed-count; `ApplicationConfiguration`
  logs both.
