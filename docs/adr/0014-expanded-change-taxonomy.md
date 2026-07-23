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

## Addendum (2026-07-23): nullability, constraint tightening, content types, security

The four categories the first round deferred were built the same day, once
asked "is the rest feasible?" Same file set, same mirroring-an-existing-rule
approach:

### 6. `PropertyShape` gains `nullable` and `Constraints`, via a backward-compatible constructor

`SpecModel.PropertyShape` needed two more facts per property: the OpenAPI
`nullable` keyword, and numeric/string bounds (`minimum`, `maximum`,
`minLength`, `maxLength`) as a nested `Constraints` record (`Constraints.NONE`
when a schema declares none). Adding a canonical 5-arg constructor would have
forced every existing call site (`OpenApiSpecReader`, several
`SpecDiffEngineTest` helpers) to change; instead `PropertyShape` keeps its
original 3-arg constructor as an explicit overload delegating to the fuller
one with `nullable=false, constraints=Constraints.NONE`. Every pre-existing
call site kept compiling unchanged. `ParameterShape` wraps the same
`PropertyShape`, so parameters got type/format comparison "for free" in the
first round and could have gotten nullable/constraint comparison the same
way — deliberately not done (see below), to keep this round's scope to
schema properties.

### 7. `PROPERTY_NULLABLE_CHANGED` is symmetric, matching `PROPERTY_REQUIRED_CHANGED`'s precedent

A property becoming nullable (consumers may not null-check) and becoming
non-nullable (consumers already null-checking lose nothing) are not equally
risky, but `PROPERTY_REQUIRED_CHANGED` already accepted exactly this kind of
simplification — both directions are `POTENTIALLY_BREAKING`, not modelled
per-direction. Matching that precedent kept the classifier's mental model
uniform rather than introducing a one-off asymmetric rule.

### 8. Constraint tightening is one-directional by design; pattern/regex is out of scope

`SpecDiffEngine` only ever emits `PROPERTY_CONSTRAINT_TIGHTENED` — loosening
a bound (raising `maxLength`, lowering `minimum`) is never reported, because
it cannot reject a previously-valid value and so isn't classifier-relevant.
Detected dimensions are `minimum`/`maximum`/`minLength`/`maxLength` only;
`pattern` tightening is **not** modelled at all, in either direction —
regex-containment ("does every string matching the new pattern also match
the old one?") is undecidable in general, so no honest deterministic rule
exists to write. This is a hard scope boundary, not a "future work" gap.

### 9. Request-body content types are tracked separately from the JSON schema reference, and can double-report with it

`requestBodyContentTypes` (the full media-type key set, e.g. `application/json`,
`application/xml`) is tracked independently of `requestBodySchema` (which only
ever looks at the `application/json` entry, unchanged since ADR-0002). If a
request body's only content type flips from JSON to XML, both
`REQUEST_BODY_REMOVED` (the JSON schema reference disappeared) and
`REQUEST_BODY_CONTENT_TYPE_REMOVED` (the `application/json` media type
disappeared) fire for the same underlying edit. This is accepted, not a bug —
consistent with the schema-removal double-signal already accepted in the
first round (`diffSchemas`' fallback firing alongside an endpoint-level
change): the two facts are genuinely different (payload shape vs. wire
format) and both are worth a human's attention.

**Response-status-specific content types are out of scope.** Each response
status can declare its own content-type set in OpenAPI, but tracking that
per-status would multiply the model's complexity for a rarer edit than a
request body's format changing; only the request body's content types are
tracked.

### 10. Security requirements are read per-operation with correct fallback-to-global semantics

`Operation.getSecurity()` returning `null` (unset) must inherit the
document's global `security:` block, while returning an empty list (explicit
`security: []`) must not — that empty list *is* the operation's requirement
("no security"), not an absence of one. `OpenApiSpecReader.securitySchemes`
distinguishes these with a plain `!= null` check before falling back, which
swagger-parser's object model supports directly. Only the *set of scheme
names* required is compared (`SECURITY_REQUIREMENT_ADDED`/`_REMOVED`), not
scope lists (an OAuth2 requirement gaining an extra scope) or
`components.securitySchemes` definition changes (a scheme's type or flow
changing) — both left for a future round if ever needed, since the
per-operation requirement is the more common and more consumer-visible edit.

### 11. Classification for the new pair types mirrors the required-addition pattern, not the removal pattern

`SECURITY_REQUIREMENT_ADDED` is `POTENTIALLY_BREAKING` (mirrors adding a
required parameter/property: existing callers without the new credential are
newly rejected) and `SECURITY_REQUIREMENT_REMOVED` is `NON_BREAKING`
(existing credentialed callers are unaffected by less being required).
`REQUEST_BODY_CONTENT_TYPE_REMOVED` is `BREAKING`/`_ADDED` is `NON_BREAKING`,
following the general removal/addition convention directly.

## Deliberately not built (either round)

Regex `pattern` tightening (undecidable, see above), per-response-status
content types (scope decision, see above), security scope-list and
scheme-definition changes (see above), and nullability/constraint tightening
for *parameters* rather than schema properties (the underlying
`PropertyShape` already carries the data; `diffSharedParameters` was not
extended to check it, purely a scope cut to keep this round's diff to
schema properties, where these two categories are far more common in
practice). `docs/roadmap.md`'s FR-034 entry reflects exactly this remaining
scope.

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
- The second round's `PropertyShape` constructor change was verified
  backward-compatible by full compilation, not just by inspection: every
  pre-existing 3-arg call site across `OpenApiSpecReader` and
  `SpecDiffEngineTest` compiled unchanged.
