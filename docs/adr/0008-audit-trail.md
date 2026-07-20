# ADR-0008: Audit trail as a distinct, retention-exempt, principal-attributed log

- Status: accepted
- Date: 2026-07-20

## Context

FR-025 requires "every state transition, approval decision and repository
mutation" recorded to an append-only audit log with principal, timestamp,
run ID and plan hash, exportable as JSON and immutable through the API. The
codebase already has `RunEventLog` (FR-019): a rich, per-run operational
timeline that powers the dashboard and SSE streaming, and is deliberately
*not* durable — FR-023's retention policy deletes it along with finished
runs past a configurable age. That deletion is correct for its purpose
(operational noise shouldn't accumulate forever) but wrong for a compliance
record, which needs to outlive the run it describes.

## Decision

1. **A second, narrower log, not a reuse of `RunEventLog`.** `AuditTrailPort`
   (`application.port`) stores `AuditEntry` (`domain`) — id, run ID,
   repository ID, principal, `AuditEventType` (`STATE_TRANSITION` /
   `APPROVAL_DECISION` / `REPOSITORY_MUTATION`), detail, plan hash, occurred-at.
   `JdbcAuditTrail` persists to a new `audit_log` table (Flyway V3, both
   vendor folders) with **no foreign key to `runs`**, specifically so
   `RetentionService` purging a finished run's row and its `run_events` never
   touches `audit_log` — nothing had to change in `RetentionService` for this
   to be true; the absence of a reference *is* the guarantee.
2. **One principal for now, not per-action attribution.** FR-024
   (authentication) doesn't exist yet, so there is no real caller identity to
   record. Rather than fabricate a human/system distinction the system can't
   actually observe, every entry is attributed to a single operator-configured
   principal (`contractguard.audit.default-principal`, default
   `local-operator`). This is a known, documented placeholder — FR-024 should
   replace it with the authenticated principal per request, not add a second
   attribution mechanism alongside it.
3. **Every transition, called out explicitly at each call site** — mirroring
   how `RunEventLog.append` is already called explicitly throughout
   `AnalysisPipeline`, `ApprovalService`, `ExecutionService` and
   `PublishService` — rather than a generic interceptor (e.g. a
   `RunRepository` decorator that diffs old vs. new state on every `save`).
   The decorator was considered and rejected: it would double reads on every
   save for a marginal reduction in call sites, and it can't distinguish
   *why* a transition happened, which the explicit call sites already know
   for free (they're already computing the message for `RunEventLog`).
4. **Read-only through the API.** `AuditController` exposes only
   `GET /api/audit` (optionally filtered by `runId` or `repositoryId`); no
   route can update or delete an entry, and neither can `RetentionService`.
   `AuditTrailService` is the single point every other service goes through
   (not the raw port), so ID generation and principal attribution live in
   exactly one place.

## Consequences

- `audit_log` grows unbounded until an operator decides otherwise — there is
  deliberately no retention/deletion path for it yet, matching "immutable
  through the API." A future export-and-truncate story, if ever needed, is
  out of scope here.
- Every future mutating action must remember to call
  `AuditTrailService` alongside `RunEventLog` — this is the same discipline
  the codebase already relies on for the operational timeline, not new risk.
- FR-024 (OIDC) becomes a drop-in principal replacement: swap the configured
  default for the authenticated caller in `AuditTrailService`'s constructor
  call sites, no schema or port change needed.
