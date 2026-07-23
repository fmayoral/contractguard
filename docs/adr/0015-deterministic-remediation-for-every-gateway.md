# ADR-0015: Deterministic Remediation Pre-Transform for Every Gateway

- Status: accepted
- Date: 2026-07-24

## Context

Real (non-mock) runs against a live LLM consistently needed the single
bounded repair attempt — none passed validation on the first try. Reading
the actual code (not guessing) found two compounding causes:

1. `ExecutionService.patchAndValidate` only ever gives the repair agent real
   `mvnw verify` output (`failureOutput`); the first attempt is architecturally
   blind by design — that asymmetry is inherent to what "repair" means and is
   not what this ADR changes.
2. `DeterministicRemediation` — exact, tested, idempotent text-surgery for
   the three mechanical categories (`ENDPOINT_RENAMED`, `PROPERTY_RENAMED`,
   `ENUM_VALUE_REMOVED`) — was wired **only** into `ScriptedLlmGateway`
   (mock mode). A real LLM had to free-write whole files for these
   provably-mechanical categories from prose alone, on every attempt,
   with zero structural help. The bundled demo scenario is built almost
   entirely from these three categories, so this alone plausibly explains
   the observed 100% first-attempt failure rate.

Only the second cause is fixable without weakening the repair concept, and
it's a clean, low-risk fix: give every gateway the same mechanical head
start mock mode already had.

## Decisions

### 1. `DeterministicRemediation` moves from `adapter.llm` to `domain`

It has zero framework imports — already domain-shaped deterministic policy,
the same category as `ClassificationPolicy`. The move lets
`ExecutionService` (application layer) call it directly without violating
the hexagonal rule that application code depends only on domain + JDK.
`ScriptedLlmGateway`'s own behaviour is completely unchanged; it just gains
one cross-package import.

### 2. `ExecutionService` pre-transforms every approved file before every attempt, not just mock mode

`applyPatch` (renamed internals: the new `proposeRewrites` helper) now:
runs `DeterministicRemediation.applyToChangedFiles` over the just-read
approved files against the run's real `ApiChange`s, builds the baseline the
agent edits from (unchanged files as-is, mechanically-fixed files with the
fix already applied), and merges the deterministic rewrites with whatever
the agent additionally proposes — the agent's version wins per-path since
it was built on top of the deterministic baseline anyway, so there is never
a conflict between the two sources, only layering.

This runs identically on the repair attempt. Since repair reads
already-patched content (attempt 1's patch was genuinely applied to the
working branch before repair starts) and the transforms are idempotent,
the pre-transform correctly no-ops there when attempt 1 already handled the
mechanical part — repair semantics are completely unchanged from before
this ADR.

### 3. The model may now legitimately propose zero further changes

`ImplementationAgent.validate()` unconditionally rejected an empty `files`
array as "no file changes proposed." That was correct before this change —
a plan always required *some* free-text-driven edit — but is no longer
always true: if the pre-transform already fully solves the plan
mechanically, the honest model response is "nothing further needed," not a
fabricated edit. `propose()`/`validate()` gained a `deterministicChangesMade`
boolean (true exactly when `ExecutionService`'s pre-transform changed at
least one file this attempt) that suppresses that one violation. Every
other validation rule (approved-path containment, non-blank content) is
unaffected.

### 4. The timeline records the mechanical step as its own entry

A new `"patch"/"MECHANICAL"` event fires before the existing `"STARTED"`
event whenever the pre-transform changes at least one file, naming how many
— consistent with this project's audit-everything habit, and useful for
telling apart "the model did nothing because the plan was already solved"
from "the model silently failed to act."

## Consequences

- No prompt changes. The attempt-1/attempt-2 asymmetry around
  `failureOutput` (cause #1 above) is untouched and out of scope here —
  fully removing it would mean giving attempt 1 some form of static
  analysis or a dry-run compile, a larger change left for a future FR.
- `DeterministicRemediationTest` moved packages with zero logic changes.
- New `ExecutionServiceTest` cases cover: an all-mechanical plan where the
  agent returns zero files still produces a successful patch, and the new
  timeline event's exact wording. New `ImplementationAgentTest` cases cover
  both the relaxed and the still-enforced empty-response validation.
- Content-based, not type-based: the pre-transform only changes a file
  whose text actually contains a matching old value — a mechanical
  `ApiChange` doesn't force a rewrite of files that don't reference it.
