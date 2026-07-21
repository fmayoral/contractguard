# ContractGuard AI — Architecture

## Overview

ContractGuard is a modular monolith with hexagonal architecture: a
framework-free domain core, an application layer that orchestrates through
ports, and adapters that hold every technology detail (Spring, H2,
swagger-parser, java-diff-utils, the git CLI, process execution, the LLM
HTTP API).

```mermaid
flowchart TD
    UI["Web UI<br/>(React / Vite)"] -->|"REST + SSE"| WEBADAPTER["adapter.web<br/>(reaches the rest of the system<br/>only through application services)"]
    WEBADAPTER --> SVC

    subgraph APP["application (framework-free)"]
        direction TB
        SVC["service<br/>AnalysisPipeline, ExecutionService, ApprovalService,<br/>RunService, RunQueryService, ReportService, EvidenceCollector,<br/>RemoteRepositoryService, PublishService (FR-027),<br/>AuditTrailService (FR-025), SpecSourceService (FR-043)"]
        AGENT["agent<br/>ChangeExplainer, ImpactInvestigator,<br/>MigrationPlanner, ImplementationAgent<br/>(LLM-assisted, schema-validated)"]
        LLMC["llm<br/>PromptLibrary, LlmJsonClient<br/>(retry-once validation)"]
        POLICY["policy<br/>WorkspacePolicy, SecretRedactor,<br/>RepositoryLock (FR-032)"]
        PORT["port (interfaces only)<br/>OpenApiDiffPort, RepositorySearchPort, SourceReaderPort,<br/>GitWorkspacePort, PatchPort, BuildValidationPort, LlmGateway,<br/>JsonCodec, RunRepository, RunEventLog, ArtifactStore,<br/>RemoteGitPort, PullRequestPort, RemoteRepositoryRegistry (FR-027),<br/>AuditTrailPort (FR-025), ObservabilityPort (FR-029),<br/>SpecSourceRegistry (FR-043)"]
        SVC --> AGENT
        AGENT --> LLMC
        SVC --> POLICY
        SVC --> PORT
    end

    SVC --> DOMAIN
    PORT -. implemented by .-> ADAPTERS

    subgraph DOMAIN["domain (no dependencies but the JDK)"]
        D["AnalysisRun (aggregate + state machine), ApiChange,<br/>ImpactEvidence, ImpactAssessment, MigrationPlan, Approval,<br/>PatchArtifact, ValidationResult, ClassificationPolicy,<br/>PlanHasher, RemoteRepository, AuditEntry,<br/>typed failures (ContractGuardException / RunFailure)"]
    end

    subgraph ADAPTERS["adapters (every technology detail lives here)"]
        A1["diff<br/>(swagger-parser)"]
        A2["search<br/>(filesystem)"]
        A3["git<br/>(CLI: local + credentialed remote)"]
        A4["github<br/>(PR creation, HTTP)"]
        A5["process<br/>(host Maven, or opt-in<br/>sandboxed docker run — FR-030)"]
        A6["llm<br/>(OpenAI-compatible HTTP<br/>+ deterministic scripted)"]
        A7["persistence<br/>(H2 / PostgreSQL, Flyway-managed,<br/>incl. audit_log — FR-025,<br/>spec_source_repositories — FR-043)"]
        A8["security<br/>(AES-GCM credential cipher)"]
        A9["artifacts / json<br/>(files, Jackson)"]
        A10["web / cli<br/>(Spring MVC, headless CI gate)"]
        A11["observability<br/>(Micrometer Observation →<br/>OTel + Prometheus — FR-029)"]
    end

    ADAPTERS --> DOMAIN
```

Layering is enforced by a build-breaking ArchUnit suite
(`HexagonalArchitectureTest`): domain depends on nothing but the JDK,
application depends only on domain and its own ports, and the web adapter
reaches the rest of the system exclusively through application services —
the dotted "implemented by" edge above is the only place adapters and
application ever meet.

## Workflow state machine

`RunState` encodes the only legal transitions; `AnalysisRun.transitionTo`
rejects everything else. The single entry into any mutating state
(`PREPARING_BRANCH`) is `AWAITING_APPROVAL`, and `AnalysisRun.recordApproval`
only accepts a decision whose plan hash matches the current plan — the
approval gate is a backend invariant, not UI behaviour.

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> VALIDATING_INPUT
    VALIDATING_INPUT --> DIFFING
    DIFFING --> SEARCHING
    SEARCHING --> ASSESSING
    ASSESSING --> PLANNING
    PLANNING --> AWAITING_APPROVAL
    AWAITING_APPROVAL --> REJECTED : rejected
    AWAITING_APPROVAL --> PREPARING_BRANCH : approved
    PREPARING_BRANCH --> PATCHING
    PATCHING --> VALIDATING
    VALIDATING --> SUCCEEDED
    VALIDATING --> REPAIRING : first attempt failed
    REPAIRING --> VALIDATING : one repair attempt only
    VALIDATING --> FAILED : repair also failed
    SUCCEEDED --> PUBLISHING : POST /runs/{id}/publish
    PUBLISH_FAILED --> PUBLISHING : retry, corrected credential
    PUBLISHING --> PUBLISHED
    PUBLISHING --> PUBLISH_FAILED

    REJECTED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
    PUBLISHED --> [*]

    note right of PLANNING
        Every non-terminal state above may also
        transition directly to FAILED or CANCELLED
        (omitted here for clarity — see
        RunState.successors() for the exact set).
    end note
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

## Actors and integration

One full cycle — analysis through an optional publish — and every actor it
touches. The dashboard never talks to the LLM, Git or GitHub directly; it
only ever calls the backend, which is the sole point of contact for every
external system.

```mermaid
sequenceDiagram
    actor Operator
    participant Dashboard as Dashboard (Web UI)
    participant Backend as ContractGuard Backend
    participant LLM as LLM Provider
    participant Repo as Consumer Repository
    participant GitHub

    Operator->>Dashboard: pick repository + old/new specs
    Dashboard->>Backend: POST /api/runs
    Backend-->>Dashboard: SSE timeline (streamed throughout)

    Backend->>Backend: deterministic diff + classification
    Backend->>LLM: explain each change
    LLM-->>Backend: explanations (schema-validated)
    Backend->>Repo: search for evidence (file + line)
    Repo-->>Backend: matches
    Backend->>LLM: assess impact (bounded tool loop)
    LLM-->>Backend: assessments (evidence-cited)
    Backend->>LLM: propose migration plan
    LLM-->>Backend: plan (files, tests, risk, rollback)
    Backend-->>Dashboard: AWAITING_APPROVAL

    Operator->>Dashboard: review plan, Approve
    Dashboard->>Backend: POST /approval (exact plan hash)
    Operator->>Dashboard: Execute remediation
    Dashboard->>Backend: POST /execute

    Backend->>Repo: verify clean, create working branch
    Backend->>LLM: propose file changes
    LLM-->>Backend: rewritten files (approved files only)
    Backend->>Repo: git apply --check, then apply
    Backend->>Repo: run consumer build/tests
    Repo-->>Backend: BUILD SUCCESS / FAILURE
    Backend-->>Dashboard: SUCCEEDED (or one repair attempt, then FAILED)

    Operator->>Dashboard: Publish (only for a registered remote repository)
    Dashboard->>Backend: POST /publish
    Backend->>Repo: commit working branch
    Backend->>GitHub: push branch
    Backend->>GitHub: open draft pull request
    GitHub-->>Backend: pull request URL
    Backend-->>Dashboard: PUBLISHED (PR link)
```

Every arrow above is also an audited fact: the deterministic steps (diff,
search, patch, build) are authoritative and never revised by the LLM; the
LLM-assisted steps are always schema-validated with one retry before a typed
failure; and every state transition, approval decision and repository
mutation is written to the append-only audit log (FR-025) regardless of
which actor triggered it.

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
