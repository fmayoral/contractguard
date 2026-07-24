# ADR-0016: Outbound Webhook Notifications for Runs Needing Attention

- Status: accepted
- Date: 2026-07-24

## Context

The in-app "action required" toast (built earlier this session) already
tells a user, wherever they are in the dashboard, the moment a run reaches
`AWAITING_APPROVAL`, `SUCCEEDED`, `FAILED` or `PUBLISH_FAILED` — the four
states `RunState.needsHumanAttention()` now names explicitly. That only
helps someone with the dashboard open. FR-031 (roadmap Phase 1) asks for
the same signal to reach a team's chat channel, so an approver doesn't have
to keep a tab open to notice a run is waiting on them.

Scoped deliberately small: one generic outbound webhook, not a Slack/Teams
SDK integration, per-repository configuration, or a notification-history
UI. Those are additive if ever needed; this slice is the whole value at a
fraction of the cost.

## Decisions

### 1. A generic webhook, not a vendor SDK

`WebhookNotificationAdapter` POSTs plain JSON with a top-level `text`
field — Slack's incoming-webhook format needs nothing more, and any other
receiver (a custom endpoint, Teams via one of its connector shims, a local
test listener) can read the structured `runId`/`runName`/`repositoryId`/
`state` fields instead. This mirrors the project's existing precedent
(`GitHubPullRequestAdapter`, `OpenAiCompatibleLlmGateway`) of `java.net.http`
over a vendor SDK — see ADR-0003.

### 2. Hooked into `AuditTrailService.recordTransition`, not a new call site per pipeline step

Every state transition already funnels through exactly one method —
`AuditTrailService.recordTransition` — because that class's own doc
already mandates it ("every other service calls this instead of
`AuditTrailPort` directly"). Rather than adding a second call
(`notifications.notify(...)`) next to every one of the several existing
`audit.recordTransition(...)` call sites across `AnalysisPipeline`,
`ExecutionService`, `PublishService` and `RunService`, the notification
fires from inside that one existing chokepoint, gated on
`to.needsHumanAttention()`. One class changed instead of four; the audit
entry is always recorded first, so the durable compliance record never
depends on whether the notification succeeds.

This does mean `AuditTrailService` now has two responsibilities (record,
and notify). Weighed against introducing a second, parallel funnel that
every future transition site would also have to remember to call, the
chokepoint reuse is the smaller risk — and the project already has a
precedent for piggybacking on this exact recording point
(`MetricsRecordingAuditTrail` derives Prometheus counters from the same
audit entries, decorating `AuditTrailPort` instead; notifications need the
strongly-typed `RunState` parameter `recordTransition` already receives,
not a re-parse of the persisted `AuditEntry.detail()` string, so decorating
the service method directly was simpler than decorating the port).

### 3. Fire-and-forget: async, never throws, never retries

`NotificationPort.notify` returns `void` and its one contract is "never
throw" — a slow or unreachable webhook endpoint must never add latency to
the pipeline step that triggered it, let alone fail the run. The adapter
sends via `HttpClient.sendAsync` (non-blocking) and every failure mode —
a build-time bad URL, a connection error, a non-2xx response — is caught
and logged, never propagated. There is no retry and no persisted delivery
record: this is an at-most-once, best-effort side channel. The audit trail
and dashboard remain the durable, authoritative record of what happened;
losing a single webhook delivery loses a convenience ping, not information.

### 4. Opt-in, off by default, no dashboard-URL assumption

`contractguard.notifications.webhook-url` blank (the default) wires
`NoOpNotificationPort` instead — the same conditional-bean shape
`ExecutionConfiguration` already uses to choose between sandboxed and host
build validation. The optional `dashboard-base-url` property adds a
clickable `url` field to the payload only when set, rather than assuming
the server can construct its own public URL — the server has none, the
same reasoning ADR-0007 already used to justify GitHub-blob links instead
of ContractGuard links in pull request bodies.

## Consequences

- `AuditTrailService`'s constructor gained a required `NotificationPort`
  parameter; all six existing direct-construction test call sites were
  updated to pass `NoOpNotificationPort` (no behaviour they test changes).
- New `RunState.needsHumanAttention()` is the single authority for "which
  states alert," shared by this feature's intent with the frontend's
  (separately maintained, since it's a different language) notifiable-state
  list — keeping the two in sync going forward means updating both call
  sites deliberately, not automatically; there is no cross-language shared
  constant.
- `RecordingNotificationPort` (test support) captures calls for
  `AuditTrailServiceTest`'s notify/don't-notify assertions.
  `WebhookNotificationAdapterTest` uses the same `com.sun.net.httpserver`
  embedded-server pattern already established by
  `GitHubPullRequestAdapterTest`, and explicitly proves the never-throws
  contract against a 500 response, a connection refusal, and a malformed
  URL — not just against the happy path.
- Not built: per-repository webhook configuration, Slack Block Kit/Teams
  Adaptive Card rich formatting, delivery retries or a notification-history
  view. All additive behind the same `NotificationPort` if ever needed.
