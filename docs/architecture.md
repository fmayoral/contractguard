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
  │             RunService, RunQueryService, ReportService, EvidenceCollector
  ├── agent     ChangeExplainer, ImpactInvestigator, MigrationPlanner,
  │             ImplementationAgent  (LLM-assisted, schema-validated)
  ├── llm       PromptLibrary, LlmJsonClient (retry-once validation)
  ├── policy    WorkspacePolicy, SecretRedactor
  └── port      OpenApiDiffPort, RepositorySearchPort, SourceReaderPort,
                GitWorkspacePort, PatchPort, BuildValidationPort,
                LlmGateway, JsonCodec, RunRepository, RunEventLog, ArtifactStore
  │
domain          AnalysisRun (aggregate + state machine), ApiChange,
                ImpactEvidence, ImpactAssessment, MigrationPlan, Approval,
                PatchArtifact, ValidationResult, ClassificationPolicy,
                PlanHasher, typed failures (ContractGuardException/RunFailure)
  │
adapters        diff (swagger-parser), search (filesystem), git (CLI),
                process (allow-listed argv), llm (OpenAI-compatible HTTP +
                deterministic scripted), persistence (H2), artifacts (files),
                json (Jackson), web (Spring MVC)
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
```

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

H2 (file mode) stores the run aggregate as a versioned JSON document plus an
append-only `run_events` table (ADR-0004). Large artifacts (patches,
validation logs, reports) live in run-scoped directories under the storage
root. Completed runs survive restarts; in-flight runs are finalised as
FAILED on startup with an explanatory failure.

## Observability

Every run has a trace ID; every step, tool call, LLM call (with prompt
name/version), approval and mutation appends a structured event. The event
log powers the SSE stream (with `Last-Event-ID` replay), the UI trace
timeline, and the report's trace section.

See the ADRs in [docs/adr](adr/) for the reasoning behind the major choices.
