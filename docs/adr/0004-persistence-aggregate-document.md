# ADR-0004: H2 embedded database storing the run aggregate as a JSON document

- Status: accepted
- Date: 2026-06-06

## Context

Completed runs must survive restart (§15) and be listable/reopenable
(FR-021). The run aggregate is deep (changes, evidence, assessments, plan,
approval, patches, validations) and always loaded/saved whole by the
workflow; nothing queries inside it relationally.

## Decision

Use embedded H2 in file mode with two tables:

- `runs(id, name, state, created_at, updated_at, payload_json, payload_version)`
  — the aggregate serialised with a dedicated persistence model (not the
  domain classes), versioned for future migration;
- `run_events(run_id, seq, occurred_at, step, status, message, metadata_json)`
  — append-only event log powering SSE replay (`Last-Event-ID`) and history.

Large artifacts (tool outputs, redacted LLM records, patches, validation
logs, reports) live in run-scoped filesystem directories, referenced by ID.

## Consequences

- Minimal schema, transactional save of a consistent aggregate, trivial
  restart survival; run listing uses the indexed columns.
- No ad-hoc SQL over nested structures — acceptable, the API serves nested
  data from the loaded aggregate.
- The persistence JSON model is an adapter concern; domain stays
  serialisation-free (ArchUnit-checked).
