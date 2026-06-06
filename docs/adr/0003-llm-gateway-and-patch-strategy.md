# ADR-0003: LLM gateway without SDK; agents propose file contents, tools compute diffs

- Status: accepted
- Date: 2026-06-06

## Context

One configurable OpenAI-compatible provider must sit behind an abstraction;
tests must never call a live model; the implementation agent must produce a
minimal, safe unified diff (FR-013). Model-authored raw diffs are notoriously
fragile (context drift, wrong hunk offsets) and hard to bound.

## Decision

1. `LlmGateway` is a port taking a versioned prompt reference plus a bounded,
   structured request payload and returning raw model text; callers validate
   the text against a JSON schema contract (typed parse, unknown fields
   rejected, semantic checks such as evidence-ID existence), retrying once
   with validation feedback before failing typed.
2. The real adapter speaks the OpenAI-compatible `/chat/completions` HTTP API
   directly via `java.net.http` — no vendor SDK dependency.
3. A scripted deterministic gateway serves mock mode and all automated tests.
   It computes schema-valid responses from the request payload, including a
   working remediation for the seeded demo scenario.
4. The implementation/repair agents return **complete new file contents** for
   approved files only. The backend computes the minimal unified diff itself
   (java-diff-utils), then enforces FR-013/FR-014 (`git apply --check`,
   path allow-list, no binary, no secrets) before application.

## Consequences

- No test or default demo run needs network access or an API key.
- Patches are always syntactically valid and minimal by construction; the
  model cannot smuggle changes into unapproved files because the diff is
  computed only for approved paths.
- Trade-off: whole-file responses cost more tokens than diffs on a real
  provider — acceptable for MVP file sizes and bounded by config limits.
