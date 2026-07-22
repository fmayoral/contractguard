# ADR-0014: Expanded Change Taxonomy — Parameters, Request Body, Response Status Codes

- Status: accepted
- Date: 2026-07-23

## Context

`SpecDiffEngine` classified endpoint additions/removals/renames and schema
property additions/removals/renames/type changes/required-flag changes/enum
value changes deterministically (ADR-0002), but any other operation-level
difference on a shared endpoint — a query parameter added, a path parameter's
type changed, the request body schema swapped, a new response status code
declared — collapsed into one generic `UNKNOWN_CHANGE` with a warning that
the region was "outside the analysed categories." This under-served exactly
the changes that show up constantly in real API evolution (a new optional
filter parameter, a tightened required flag, a new error response code),
forcing every one of them into manual review regardless of how mechanical or
how severe. FR-034 (`docs/roadmap.md`) proposed exactly this expansion; this
ADR covers the first, highest-value slice of it.

## Decisions

### 1. Nine new `ChangeType` values, each a direct mirror of an existing rule

Rather than inventing new classification judgment calls, every new category
follows a convention already established by the property/enum taxonomy:
removals are unconditionally `BREAKING` (matching `PROPERTY_REMOVED`,
`ENDPOINT_REMOVED`, `ENUM_VALUE_REMOVED`), additions are `NON_BREAKING`
unless the addition is required (`PARAMETER_ADDED`, `REQUEST_BODY_ADDED`
mirror `PROPERTY_ADDED`'s `affectsRequired` parameter), type/schema changes
are `BREAKING` (mirroring `PROPERTY_TYPE_CHANGED`), and a required-flag
flip is `POTENTIALLY_BREAKING` (mirroring `PROPERTY_REQUIRED_CHANGED`).
Response status codes mirror enum values directly: adding one is
`POTENTIALLY_BREAKING` (an exhaustive `switch` on status code misses it,
exactly `ENUM_VALUE_ADDED`'s reasoning) and removing one is `BREAKING`
(handling for it becomes unreachable, exactly `ENUM_VALUE_REMOVED`'s
reasoning). This makes the new rules predictable to anyone already familiar
with the existing ones, and keeps `ClassificationPolicy.classify` a single
exhaustive `switch` expression with no `default` — the compiler still
enforces that every category is classified.

### 2. No rename pairing for parameters or response status codes

Endpoints and schema properties get heuristic rename pairing (a removed and
an added item with matching method/response-schema, or matching
type/required-status, are paired into one `*_RENAMED` change rather than a
remove+add pair). Parameters and status codes do not: a renamed parameter
surfaces as `PARAMETER_REMOVED` + `PARAMETER_ADDED`. This is a deliberate
simplification, not an oversight — parameter names carry far less
disambiguating signal than a full endpoint or property shape, so a pairing
heuristic here would guess far more often than it would correctly infer a
rename. The engine already accepts "degrade to remove+add rather than guess
wrong" elsewhere (ambiguous property renames do the same, see
`SpecDiffEngineTest.ambiguousPropertyRenameDegradesToRemovedPlusAdded`).

### 3. Request body add/remove/schema-change are three distinct categories, not one

Unlike the old fallback (any request-body difference, including presence
itself, was one undifferentiated `UNKNOWN_CHANGE`), the new engine
distinguishes `REQUEST_BODY_ADDED`, `REQUEST_BODY_REMOVED` and
`REQUEST_BODY_SCHEMA_CHANGED` because consumer impact genuinely differs
between them (a newly *required* body is a hard break for existing callers
sending none; a removed body is a "your payload is now ignored" situation;
a schema swap means the payload shape itself must change) — collapsing them
would have thrown away exactly the distinction that makes a report useful.

### 4. `SpecModel.Endpoint`'s parameter/request-body/response fields are richer, but still confined to `adapter.diff`

`parameterNames: Set<String>` became `parameters: Map<String,
ParameterShape>` (location, required, type/format — reusing the existing
`PropertyShape`/`sameTypeAs` rather than inventing a parallel type system),
and two new fields (`requestBodyRequired`, `responseStatusCodes`) were
added. `SpecModel` remains internal to `adapter.diff` (ADR-0002's
confinement rule; verified nothing outside that package references
`Endpoint`), so this reshape has zero blast radius outside
`OpenApiSpecReader`/`SpecDiffEngine` and their tests.

### 5. Every downstream consumer of `ApiChange` needed zero changes

Before writing any diff-engine code, every consumer of `ApiChange`/
`ChangeType` was traced: `ImpactInvestigator`, `ChangeExplainer`,
`MigrationPlanner`, `ImplementationAgent`, `ReportService` and the entire
frontend are generic over the type (the frontend renders `change.type` as
plain text with no switch or label map). Only two places are exhaustive
`switch` expressions requiring new cases: `ClassificationPolicy.classify`
and `EvidenceCollector.termsFor` (search-term generation — parameter
changes search the parameter name, request-body changes search the
endpoint path plus both schema names when a schema actually changed,
response-status changes search the endpoint path). This made the whole
feature a two-file classification/evidence change plus one diff-engine
change, with the entire evidence-collection → LLM-impact-assessment →
migration-planning → reporting pipeline picking up the new categories with
no modification.

## Deliberately not built this round

Nullability changes, numeric/string constraint tightening (`minimum`,
`maxLength`, etc.), content-type changes (a JSON body becoming
`multipart/form-data`, say), and security-scheme changes remain undetected
— any such difference that doesn't also touch a category above still falls
through as no change at all (not even `UNKNOWN_CHANGE`, since the old
catch-all this ADR replaces only fired on parameter/request-body
differences). `docs/roadmap.md`'s FR-034 entry is marked partially
completed accordingly, the same honesty pattern used for FR-027 (GitHub-only)
and FR-029 (tracing/metrics without structured logs).

## Consequences

- `SpecDiffEngineTest`'s `parameterChangeSurfacesAsUnknownWithWarning` test
  was replaced by ten focused tests, one per new category plus its
  classification — the old test asserted behaviour this ADR intentionally
  changes.
- `UNKNOWN_CHANGE` still exists and is still produced by `diffSchemas` for a
  removed schema; it is no longer reachable from `diffSharedEndpoints`.
- Existing persisted `AnalysisRun`s referencing `ChangeType` by name are
  unaffected: new enum constants are purely additive, and
  `RunDocument`'s `ChangeType.valueOf(...)` deserialization does not require
  every historical value to still be producible.
