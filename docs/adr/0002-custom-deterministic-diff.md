# ADR-0002: Custom deterministic diff engine over swagger-parser

- Status: accepted
- Date: 2026-06-06

## Context

The diff must be deterministic and authoritative (FR-003), library types must
not leak into the domain (FR-004), and the demo requires the seeded scenario
to surface as four changes including an *endpoint rename* and a *property
rename*. Off-the-shelf `openapi-diff` reports renames only as unrelated
removed+added pairs and its result model is large and awkward to normalise.

## Decision

Parse both specifications with `swagger-parser` (the external library, kept
inside `adapter.diff`) and implement the diff engine ourselves, emitting
domain `ApiChange` values directly. Add deterministic rename pairing:

- a removed path and an added path with the same HTTP method and the same
  response schema become `ENDPOINT_RENAMED`;
- a removed property and an added property of identical type in the same
  schema become `PROPERTY_RENAMED`.

Pairing evidence (both sides of the pair) is recorded on the change. If a
candidate pairs ambiguously (multiple matches), the engine does not guess: it
emits the raw removed/added changes instead.

## Consequences

- Full control over categories, classification reasons and machine-readable
  evidence; small, well-tested code instead of a large mapping layer.
- Supported change categories are the ones the MVP classifies (endpoints,
  operations, properties, required flags, enum values); anything else surfaces
  as `UNKNOWN` rather than being silently dropped.
- swagger-parser model types stay confined to `adapter.diff` (ArchUnit-checked).
