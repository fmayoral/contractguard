# ADR-0017: Deterministic Spec-Diff Preview Before Starting a Run

- Status: accepted
- Date: 2026-07-24

## Context

Starting a run is the only way to see whether two specification versions
even differ. A user who picked the wrong spec pair, or just wants to sanity
check before committing to a full analysis (evidence search, several LLM
calls, a per-repository lock held for the run's whole lifetime), currently
has no way to find out short of starting one and watching it progress
through `DIFFING`.

The deterministic diff (`OpenApiDiffPort` → `SpecDiffEngine`, with
`ClassificationPolicy` already baked into every produced `ApiChange` — see
`AnalysisPipeline.diffAndExplain`) is exactly the fact a preview needs, and
it is already the first, purely deterministic thing a real run computes
before any LLM call. Exposing that one step standalone, with nothing
persisted and no LLM involved, is low-risk, additive, and cannot ever
disagree with what a real run would detect first — because it *is* that
same step.

## Decisions

### 1. `SpecResolutionService` extracted from `RunService`

Resolving a qualified spec ID (`local:`/`upload:`/`source:sourceId:`, or a
bare name treated as `local:` for CLI/legacy-run backward compatibility —
ADR-0012) was a private method on `RunService`. A preview needs the exact
same resolution with none of `RunService`'s other concerns (repository
locking, run persistence, dispatching the pipeline), so the logic moved,
unchanged, to a new standalone class both `RunService` and the new
`SpecPreviewService` depend on. `RunService` keeps `specsDirectory`/
`uploadedSpecsDirectory`/`specSources` for `listSpecOptions()` (enumerating
what's available is a distinct concern from resolving one already-chosen
ID) but now delegates every actual resolution to `SpecResolutionService`.

### 2. `SpecPreviewService` is a two-line orchestrator, not a rebuilt pipeline

```java
public OpenApiDiffPort.DiffResult preview(String oldSpecId, String newSpecId) {
    return diffPort.diff(specs.resolve(oldSpecId), specs.resolve(newSpecId));
}
```

No new diffing logic was written. Classification is already inside
`ApiChange` by the time `OpenApiDiffPort.diff` returns — nothing extra to
call. This is deliberately *not* a preview of consumer impact (that needs
evidence search across a real repository, which is exactly the expensive,
LLM-driven step a preview exists to let a user skip until they're ready).

### 3. A new `GET /api/spec-diff`, not a flag on `POST /api/runs`

The preview is idempotent and side-effect-free — a textbook `GET` — and
deliberately a separate resource from run creation rather than a "dry run"
parameter on `POST /api/runs`, so its request/response shape can never be
confused with an actual run and never needs `repositoryId` (a preview
compares two specs; it has nothing to do with any consumer repository).

### 4. Reused `RunDtos.Change`, one new mapper overload

`SpecDiffDtos.Preview` reuses the existing `RunDtos.Change` shape (already
returned inside `RunDetail.changes()`) instead of inventing a parallel DTO.
`DtoMapper.toChanges(AnalysisRun)` — coupled to a persisted run — gained a
sibling `toChanges(List<ApiChange>)` overload the preview path uses
directly; a preview's `Change.explanation` is naturally always `null`
(no LLM ran), which is the one and only difference from a real run's
change list.

### 5. `SpecPreviewService` returns its own `Preview` record, not the port's `DiffResult`

The first cut had `SpecDiffDtos.Preview.from(OpenApiDiffPort.DiffResult)` —
`HexagonalArchitectureTest` correctly failed it: adapters (`adapter.web..`)
may depend on application *services*, never on application *ports*
directly, and `OpenApiDiffPort` is a port. The fix is also the better
design regardless of the rule: `SpecPreviewService` exposes its own
`Preview(List<ApiChange>, List<String>)` record rather than leaking the
diff mechanism's own wire shape to its callers — a port's DTO is a
contract with its adapter, not necessarily the right public return type
for the application service built on top of it.

## Consequences

- Four call sites construct `RunService` (`PipelineConfiguration`,
  `CliConfiguration`, `RunServiceTest`, `CliRunnerTest`); all four updated
  to also construct/inject `SpecResolutionService`. Behaviour is unchanged
  — every existing `RunServiceTest`/`CliRunnerTest` case still passes
  verbatim, proving the extraction preserved semantics exactly.
- `SpecPreviewServiceTest` runs against the real bundled demo specs and the
  real `SwaggerOpenApiDiffAdapter` (not a fake diff port), so it proves the
  preview endpoint would show a user precisely the four changes the
  bundled demo's real run detects — not just that some port gets called.
- Not built: previewing evidence/impact (requires a real consumer
  repository and the LLM — exactly what a preview is for skipping) or
  caching repeated previews (the diff is already fast — parsing and
  comparing two OpenAPI documents — caching would be premature).
