# ContractGuard AI — Architecture

## Overview

ContractGuard is a modular monolith with hexagonal architecture: a
framework-free domain core, an application layer that orchestrates through
ports, and adapters that hold every technology detail (Spring, H2,
swagger-parser, java-diff-utils, the git CLI, process execution, the LLM
HTTP API). The layering is enforced by a build-breaking ArchUnit suite
(`HexagonalArchitectureTest`).

```
Web UI (React/Vite)
  │  REST + SSE
adapter.web ──────────────► application services only
  │
application
  ├── service   AnalysisPipeline, ExecutionService, ApprovalService,
  │             RunService, RunQueryService, ReportService, EvidenceCollector,
  │             RemoteRepositoryService, PublishService  (FR-027),
  │             AuditTrailService  (FR-025)
  ├── agent     ChangeExplainer, ImpactInvestigator, MigrationPlanner,
  │             ImplementationAgent  (LLM-assisted, schema-validated)
  ├── llm       PromptLibrary, LlmJsonClient (retry-once validation)
  ├── policy    WorkspacePolicy, SecretRedactor, RepositoryLock  (FR-032)
  └── port      OpenApiDiffPort, RepositorySearchPort, SourceReaderPort,
                GitWorkspacePort, PatchPort, BuildValidationPort,
                LlmGateway, JsonCodec, RunRepository, RunEventLog, ArtifactStore,
                RemoteGitPort, PullRequestPort, RemoteRepositoryRegistry  (FR-027),
                AuditTrailPort  (FR-025), ObservabilityPort  (FR-029)
  │
domain          AnalysisRun (aggregate + state machine), ApiChange,
                ImpactEvidence, ImpactAssessment, MigrationPlan, Approval,
                PatchArtifact, ValidationResult, ClassificationPolicy,
                PlanHasher, RemoteRepository, AuditEntry, typed failures
                (ContractGuardException/RunFailure)
  │
adapters        diff (swagger-parser), search (filesystem), git (CLI: local +
                credentialed remote clone/fetch/push), github (PR creation,
                HTTP), process (allow-listed argv: host Maven or, opt-in,
                a resource-limited `docker run` sandbox — FR-030, ADR-0010),
                llm (OpenAI-compatible HTTP + deterministic scripted),
                persistence (H2/PostgreSQL, Flyway-managed; includes the
                audit_log table, FR-025), security (AES-GCM credential
                cipher), artifacts (files), json (Jackson), web (Spring MVC),
                cli (headless CI gate), observability (Micrometer
                Observation → OpenTelemetry spans + Prometheus metrics,
                FR-029, ADR-0011)
```

## Workflow state machine

`RunState` encodes the only legal transitions; `AnalysisRun.transitionTo`
rejects everything else. The single entry into any mutating state
(`PREPARING_BRANCH`) is `AWAITING_APPROVAL`, and `AnalysisRun.recordApproval`
only accepts a decision whose plan hash matches the current plan — the
approval gate is a backend invariant, not UI behaviour.

```
CREATED → VALIDATING_INPUT → DIFFING → SEARCHING → ASSESSING → PLANNING → AWAITING_APPROVAL
AWAITING_APPROVAL → REJECTED | PREPARING_BRANCH
PREPARING_BRANCH → PATCHING → VALIDATING → SUCCEEDED | REPAIRING | FAILED
REPAIRING → VALIDATING   (structurally at most once)
any active state → FAILED | CANCELLED
SUCCEEDED | PUBLISH_FAILED → PUBLISHING → PUBLISHED | PUBLISH_FAILED   (FR-027, human-triggered, retryable)
```

`SUCCEEDED` and `PUBLISH_FAILED` are terminal for the analysis/execution
pipeline but remain publishable: `PublishService` only enters `PUBLISHING`
from an explicit `POST /runs/{id}/publish`, mirroring the approval gate — a
remediation is never pushed or opened as a pull request automatically.

**On restart** (FR-032, ADR-0009): `RunState`'s transitions are one-shot —
no state lists itself as a valid target — which is exactly what makes an
interrupted run's recovery safe rather than a design gap. A run still
`CREATED` (nothing mutated) is re-dispatched from scratch; a run in
`AWAITING_APPROVAL` is left untouched (nothing was in flight); every other
non-terminal state is finalised as `FAILED` with a clean remediation
message — deliberately not resumed mid-mutation.

## Determinism vs. agency

Deterministic and authoritative:

- OpenAPI parsing/diff/classification (`adapter.diff` + `ClassificationPolicy`)
- Repository search and evidence coordinates (`EvidenceCollector`)
- Git status, branching, `git apply --check`, patch application
- Unified-diff construction (computed by the backend from file contents)
- Build validation (`mvnw verify` through the command allow-list)
- Reports (rendered from the persisted aggregate)

LLM-assisted, always schema-validated with one retry then typed failure:

- Change explanation (cannot alter facts; one explanation per change ID)
- Impact investigation — a bounded tool loop restricted to
  `search_repository` / `read_source_file`; unregistered tools are rejected;
  every assessment must cite existing evidence IDs
- Migration planning — only evidence-backed files, only allow-listed
  validation commands
- Implementation/repair — returns complete file contents for approved files
  only; the diff is computed and safety-checked by tools

Mock mode (default) swaps only the `LlmGateway` implementation for a
deterministic scripted gateway; every validation path stays identical.

## Safety model

- All filesystem access flows through `WorkspacePolicy`: canonical-path
  containment, symlink-escape rejection, secret-file blocking.
- All process execution flows through `ProcessRunner` with fixed argument
  arrays, timeouts and output caps; no shell strings exist.
- `SecretRedactor` scrubs LLM payloads, stored logs, and rejects patches
  containing credential-shaped content.
- Patches are checked (`git apply --check`, binary rejection, blocked-file
  rejection, approved-path allow-list) before application, and are stored as
  artifacts either way.
- The MVP never commits, merges, pushes, rebases, resets, tags or touches
  remotes; those operations do not exist on any port.

## Persistence

The run aggregate is stored as a versioned JSON document plus an append-only
`run_events` table (ADR-0004). Two engines are supported behind the same
JDBC adapters: embedded H2 (file mode, the local default) and PostgreSQL for
server deployments (`postgres` Spring profile; ADR-0006). The schema is
managed by Flyway with vendor-specific migrations; databases that predate
Flyway are baselined in place. Large artifacts (patches, validation logs,
reports) live in run-scoped directories under the storage root. Completed
runs survive restarts; in-flight runs are finalised as FAILED on startup
with an explanatory failure. An optional retention window purges finished
runs, their events and artifacts after a configurable number of days.

## Observability

Every run has a trace ID; every step, tool call, LLM call (with prompt
name/version), approval and mutation appends a structured event. The event
log powers the SSE stream (with `Last-Event-ID` replay), the UI trace
timeline, and the report's trace section.

External observability (FR-029, ADR-0011) sits behind `ObservabilityPort`:
`AnalysisPipeline`, `ExecutionService`, `PublishService` and `LlmJsonClient`
wrap each named step in a span via the port, kept framework-free like every
other application-layer dependency; `adapter.observability.
MicrometerObservability` turns those spans into both OpenTelemetry traces
and Prometheus `Timer` metrics from the same instrumentation call
(Micrometer's `Observation` API). `MetricsRecordingAuditTrail` decorates the
FR-025 audit trail to derive a `runs by state` counter from the existing
state-transition record, without any application-service change. Spans
export to the log by default (no external collector required); Prometheus
scraping and Kubernetes-style health/readiness probes are exposed via Spring
Boot Actuator (`/actuator/prometheus`, `/actuator/health/{liveness,
readiness}`). Structured JSON logging is not yet built — see ADR-0011.

See the ADRs in [docs/adr](adr/) for the reasoning behind the major choices.
