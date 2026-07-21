# ContractGuard AI
## Roadmap — Production Hardening and Product Direction

**Document type:** Product roadmap
**Baseline:** the shipped MVP (FR-001 … FR-022 in [the specification](specification.md))
**Status:** Proposed
**FR numbering:** continues from the specification at FR-023

---

## 1. Purpose

The MVP proves the core loop: deterministic diff → evidence-backed impact assessment → human-approved plan → bounded remediation → validated patch → auditable report. This document defines what turns that demo-grade loop into a product an organisation would adopt and keep: production-ready code, enterprise foundations (identity, durability, CI integration), ecosystem breadth (more build systems, languages and contract types), and fleet-level insight (many consumers, many APIs, one blast-radius view).

## 2. Phase 0 — Production-Readiness Refactor (completed)

Behaviour-preserving refactors to reduce cyclomatic and cognitive complexity ahead of feature work. All 251 backend tests (including the e2e profile) must pass unchanged.

| Change | Rationale |
|---|---|
| `ReportService.renderMarkdown` decomposed into eleven single-section renderers; `reportDocument` decomposed into per-entity mappers | 150-line method with per-section branching was the largest cognitive-complexity hotspot |
| `ExecutionService.applyPatch` split into `readApprovedFiles` and `requireSafePatch` | One method mixed file loading, LLM proposal, artifact storage and three safety verdicts |
| `SpecDiffEngine` rename-pairing extracted to `classifyRemovedEndpoint` / `classifyRemovedProperty`; shared-key comparison to `diffSharedEndpoints` / `diffSharedProperties` | Deepest nesting in the codebase; pairing policy is now a named, individually readable unit |
| `RunLookup.require` replaces the `ApprovalService.notFound` static borrowed by four other services | Shared load-or-fail lookup no longer lives inside an unrelated service |

Deliberately **not** refactored: `DeterministicRemediation`'s text-surgery internals (intricate but correct, fully test-covered, and mock-mode only) and the EOL-aware diff logic in `GitCliAdapter` (Windows-checkout correctness; see ADR-0005).

## 3. Product Vision

> Any team that publishes or consumes HTTP APIs can see, before merge, exactly which consumers a contract change breaks, get an evidence-backed migration plan, and — with one approval — receive a validated remediation PR in each affected repository.

The differentiators to protect while scaling: deterministic tools stay authoritative (the model never asserts unverifiable facts), every mutation is gated by human approval against an exact plan hash, and every run leaves a complete audit trail.

## 4. Roadmap Overview

| Phase | Theme | Outcome |
|---|---|---|
| 1 | Enterprise foundations | Deployable, multi-user, durable, CI-integrated |
| 2 | Ecosystem breadth | Works with the repos and stacks organisations actually have |
| 3 | Fleet intelligence | Org-wide consumer registry, watch mode, blast-radius analytics |

Each phase is releasable on its own; later phases must not be prerequisites for value in earlier ones.

---

## 5. Phase 1 — Enterprise Foundations

### FR-023 — Durable Persistence (completed)
Replace the embedded store with PostgreSQL behind the existing `RunRepository`/`RunEventLog`/`ArtifactStore` ports. Schema managed by Flyway migrations from day one; `RunDocument.CURRENT_VERSION` governs document upgrades. H2 remains for tests and single-user local mode. Configurable retention policy for runs and artifacts.

### FR-024 — Authentication and Authorisation
OIDC single sign-on (Entra ID, Okta, Keycloak). Three roles: **Viewer** (read runs/reports), **Operator** (create and execute runs), **Approver** (decide plans). Approval records gain the authenticated principal; the report's Approval section shows who decided. Local no-auth mode stays available for single-developer use.

### FR-025 — Audit Trail (completed)
Every state transition, approval decision and repository mutation is written to an append-only audit log with principal, timestamp, run ID and plan hash. Exportable as JSON; immutable through the API. `principal` is a single operator-configured placeholder until FR-024 lands (see ADR-0008); the log survives FR-023's retention purge by design (no foreign key to `runs`).

### FR-026 — Headless CLI and CI Gate (completed)
A `contractguard` CLI that runs analysis (no remediation) against two specs and a consumer checkout, emits the JSON report, and exits non-zero on BREAKING changes above a configurable severity threshold. Packaged as a GitHub Action and GitLab CI template so a provider's PR that edits an OpenAPI spec fails fast with the impact table as a PR comment.

### FR-027 — Remote Repository Support (completed for GitHub over HTTPS; GitLab/Bitbucket/SSH remain future work)
Clone/fetch consumer repositories over HTTPS with scoped, encrypted-at-rest credentials, replacing the workspace-directory-only model. Remediation branches are pushed and opened as **draft pull requests** on GitHub instead of being left as local branches; the PR body embeds the plan hash and evidence links (as GitHub blob links into the target repository, since the server has no public URL of its own — ADR-0007). GitLab/Bitbucket and SSH access are additive behind the same `RemoteGitPort`/`PullRequestPort` ports, not yet built.

### FR-028 — LLM Provider Matrix and Cost Controls
First-class adapters for Anthropic (direct + Bedrock), Azure OpenAI and Ollama alongside the existing OpenAI-compatible gateway. Per-run token and cost accounting persisted with the run and shown in UI and reports; org-level monthly budget with hard stop. Mock mode remains the test default.

### FR-029 — Observability (partially completed: tracing, metrics and health endpoints; structured JSON logs deferred)
OpenTelemetry traces spanning each pipeline node (Micrometer Observation, one span per named step, nested under a phase-level span for analysis/execution/publish), LLM calls annotated with token counts, Prometheus metrics (runs by state via the audit trail, per-step durations, LLM token spend) and health/readiness endpoints are shipped — see ADR-0011. Structured JSON logs correlated by the existing `traceId` are not: Spring Boot's built-in structured logging needs 3.4 (this project is pinned to 3.3.5), and doing it properly means an additional `logstash-logback-encoder`/logback-config pass, deliberately left as a clean follow-up rather than a rushed partial version. Failure-category metrics are not separately broken out yet.

### FR-030 — Sandboxed Validation (completed)
Consumer builds and tests execute in a resource-limited container (CPU, memory, wall-clock, no network egress by default) rather than on the host. Build images configurable per repository. Opt-in via `contractguard.validation.docker.enabled` (off by default, preserving the zero-setup host path); "no network egress" is honored by bind-mounting the host's `~/.m2` read-only rather than opening a package-registry exception — see ADR-0010, which also documents a pre-existing `ProcessRunner` timeout bug this work surfaced and fixed.

### FR-031 — Notifications
Slack/Teams webhook and email notifications on AWAITING_APPROVAL and terminal states, with deep links to the run. Configurable per repository/team.

### FR-032 — Concurrency and Resume (completed)
A bounded run queue with per-repository mutual exclusion (generalising today's REPOSITORY_BUSY check). Runs interrupted by a restart resume from the last persisted state or finalise as FAILED with a clean remediation message — never a stuck non-terminal state. Resume is deliberately scoped to runs still `CREATED` (nothing mutated yet); every other interrupted state keeps the clean-fail fallback, since the state machine's one-shot transitions are a safety feature, not a gap — see ADR-0009 for why deeper resume was not attempted.

**Phase 1 acceptance:** a team can deploy ContractGuard behind SSO, wire the CI gate into a provider repo, analyse a private remote consumer, approve in the UI, and receive a draft PR — with the whole sequence visible in traces, metrics and the audit log.

---

## 6. Phase 2 — Ecosystem Breadth

### FR-033 — Gradle Support
`BuildValidationPort` adapter for Gradle wrapper builds (`gradlew test`), with the same timeout/output-bounding semantics as Maven.

### FR-034 — Expanded OpenAPI Change Taxonomy
Detect and classify: parameter add/remove/required/type changes, request-body schema changes, response status-code changes, content-type changes, nullability, numeric/string constraint tightening, and security-scheme changes. Each new category gets deterministic classification rules and report treatment; remediation coverage may lag detection (report as manual-migration items, mirroring today's Limitations honesty).

### FR-035 — TypeScript/JavaScript Consumer Analysis
Evidence collection and impact assessment for TS/JS consumers (fetch/axios clients, generated SDK usage), validated via the repo's package-manager test script. Remediation initially limited to the mechanical categories already supported for Java.

### FR-036 — Additional Contract Types
GraphQL schemas and gRPC/protobuf as diffable contract types behind the existing `OpenApiDiffPort` abstraction (renamed `ContractDiffPort`), each with its own change taxonomy. AsyncAPI as a stretch.

### FR-037 — Semantic Evidence Search
Embedding-based code search to supplement (never replace) deterministic text search: semantic hits are labelled as lower-confidence evidence and can never be the sole citation for a BREAKING assessment.

### FR-038 — Report Destinations
One-click export of the run report to Confluence and Jira (ticket per plan item, optional), plus signed shareable read-only links and PDF export.

**Phase 2 acceptance:** a Gradle-built Java consumer and a TypeScript consumer of the same API are both analysed for an expanded set of change categories, and reports land where the organisation already works.

---

## 7. Phase 3 — Fleet Intelligence

### FR-039 — Consumer Registry
Organisations register APIs (spec sources: URL, registry, or repo path) and consumers (repositories + which APIs they consume). The registry powers "who consumes this API?" answers without running an analysis.

### FR-040 — Watch Mode
Scheduled polling of registered spec sources; a detected spec change automatically fans out analysis runs across all registered consumers of that API, with notifications on breaking impact. Remediation still requires per-run human approval — watch mode never mutates unattended.

### FR-041 — Blast-Radius Dashboard
Fleet view: for a proposed spec change, the set of impacted consumers, severity distribution, remediation coverage (auto vs manual), and historical trend of breaking changes per API/team.

### FR-042 — Multi-Run Campaigns
One approval workflow spanning the fan-out: an approver reviews per-consumer plans in a single campaign view and approves them individually or in bulk (bulk approval still binds each decision to that run's exact plan hash).

**Phase 3 acceptance:** a platform team registers 20 consumers of a core API, a provider proposes a v2 spec, and within minutes the dashboard shows exactly which teams break, with draft-PR remediations queued behind approvals.

---

## 8. Non-Functional Requirements

- **NFR-1 Safety invariants are non-negotiable:** approval-before-mutation, plan-hash binding, approved-files-only patches, bounded repair attempts and secret redaction apply to every new execution path (CI, watch mode, campaigns).
- **NFR-2 Determinism:** all classification, evidence collection, diffing and reporting remain reproducible; LLM output remains schema-validated and evidence-cited. No test may call a live model.
- **NFR-3 Performance:** analysis of a 100-endpoint spec pair against a 500-kLOC consumer completes in under 5 minutes excluding the consumer's own test runtime; UI event streaming latency under 1 s.
- **NFR-4 Quality gates:** every phase ships with the existing gates (unit, integration, e2e, ArchUnit hexagonal rules) plus static-analysis complexity budgets in CI — cyclomatic ≤ 10 and cognitive ≤ 15 per method, enforced so Phase 0's gains are not eroded.
- **NFR-5 Deployability:** single `docker compose up` for evaluation; Helm chart for Kubernetes; zero-downtime schema migrations.
- **NFR-6 Data protection:** credentials encrypted at rest, artifacts access-controlled by role, configurable retention, no source code sent to LLM providers beyond the bounded excerpts already defined by the tool contracts.

## 9. Out of Scope (all phases)

- Autonomous merging — a human merges every remediation PR
- Runtime traffic inspection or API gateway functionality
- Spec authoring/linting (complements Spectral et al., does not replace them)
- Windows-service packaging; server deployment targets Linux containers

## 10. Open Questions (to resolve before Phase 1 build)

1. Postgres-only, or keep an embedded durable option for air-gapped single-node installs?
2. Draft-PR flow: push from ContractGuard's credential or a per-team bot identity?
3. Is the CI gate a paid differentiator or the free wedge that drives adoption?
4. Which LLM providers do target customers actually permit? (Determines adapter priority in FR-028.)
