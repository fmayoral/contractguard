# ContractGuard AI
## Agentic API Change Impact and Remediation Platform

**Document type:** Product and engineering specification
**Status:** Implemented

---

## 1. Executive Summary

ContractGuard AI is a local-first agentic engineering tool that compares two OpenAPI specifications, classifies contract changes, finds affected usages in a Java consumer repository, creates an evidence-backed migration plan, pauses for human approval, applies a minimal patch on an isolated Git branch, runs the consumer test suite, and produces an auditable report.

The project must demonstrate genuine bounded agent behaviour rather than a generic chatbot. The agent may reason, investigate, plan, explain, and propose code changes. Deterministic tools remain authoritative for API differences, repository evidence, Git state, patch application, and test outcomes.

The MVP is intentionally constrained to:

- OpenAPI 3.x specifications
- One Java 21 / Spring Boot consumer repository
- Maven builds
- One bundled demonstration scenario
- Local execution
- One configurable LLM provider behind an abstraction
- Human approval before any source modification

---

## 2. Problem Statement

API providers frequently change contracts without fully understanding the impact on downstream consumers. Specification diff tools can identify changed endpoints and schemas, but they usually do not:

- Discover affected source files
- Explain runtime or compilation impact
- Produce a migration plan
- Apply a controlled remediation
- Validate the remediation
- Preserve evidence and an audit trail

ContractGuard closes that gap by combining deterministic engineering tools with a governed agentic workflow.

---

## 3. Goals

The MVP must:

1. Compare old and new OpenAPI specifications.
2. Identify and classify contract changes.
3. Distinguish breaking, potentially breaking, non-breaking, and unknown changes.
4. Search a Java consumer repository for affected usages.
5. Provide file-and-line evidence for every reported code impact.
6. Generate a structured migration plan.
7. Require explicit approval before code modification.
8. Create an isolated Git branch.
9. Generate and safely apply a minimal patch.
10. Compile and run automated tests.
11. Permit at most one bounded repair attempt.
12. Produce Markdown and JSON reports.
13. Display workflow progress, evidence, decisions, and results in a simple UI.
14. Run locally from documented commands.

---

## 4. Non-Goals

The MVP must not include:

- SaaS multi-tenancy or user registration
- Enterprise identity integration
- Remote Git hosting or Jira integration
- Arbitrary cloning from untrusted URLs
- Multiple programming languages
- Autonomous merge, push, deployment, or release
- Kubernetes
- Distributed execution
- A general coding assistant
- A generic RAG chatbot
- Vector search without a demonstrated need
- Modification without approval
- Unrestricted shell access
- Guaranteed remediation for every OpenAPI change type

Future extensions may be documented, but they must not dilute the MVP.

---

## 5. Users

### API Provider Engineer
Needs to know whether a proposed contract change will break a known consumer.

### Consumer Engineer
Needs evidence of affected code and a safe migration proposal.

### Consultant or Reviewer
Needs a clear demonstration of agent orchestration, governance, code quality, traceability, and measurable outcomes.

---

## 6. Bundled Demo Scenario

### 6.1 Old Contract

The old specification exposes:

- `GET /customers/{id}`
- Response schema `Customer`
- Required field `fullName: string`
- `status` enum values:
  - `ACTIVE`
  - `SUSPENDED`
  - `CLOSED`

### 6.2 New Contract

The new specification introduces:

1. Endpoint rename:
   - `GET /customers/{id}`
   - becomes `GET /v2/customers/{id}`

2. Field rename:
   - `fullName`
   - becomes `displayName`

3. Removed enum value:
   - `SUSPENDED` is removed

4. Optional field added:
   - `preferredLanguage: string`

### 6.3 Sample Consumer

The bundled Java repository must contain:

- A client calling `/customers/{id}`
- A DTO containing `fullName`
- Business logic handling `SUSPENDED`
- Tests tied to the old contract
- At least one documentation or configuration reference to the old endpoint

### 6.4 Expected Outcome

ContractGuard must:

- Detect all four changes
- Mark endpoint rename, field rename, and enum removal as breaking
- Mark the optional field addition as non-breaking
- Find all relevant consumer usages with line evidence
- Generate a migration plan
- Apply an approved patch
- Update or add tests
- Run `./mvnw verify`
- Produce a successful report
- Preserve the original branch

---

## 7. User Journeys

### 7.1 Analyse
1. Select the bundled repository.
2. Select old and new specifications.
3. Start analysis.
4. View streamed progress.
5. Review detected changes and impacted files.

### 7.2 Approve
1. Review the migration plan.
2. Review proposed file changes, risks, and validation.
3. Approve or reject.
4. Rejection ends without modifying the repository.
5. Approval records the exact plan hash.

### 7.3 Remediate
1. Confirm the repository is clean.
2. Create a dedicated branch.
3. Generate a minimal unified diff.
4. Validate the patch.
5. Apply it.
6. Run `./mvnw verify`.
7. If validation fails, permit one corrective attempt.
8. Record the final result without merging or pushing.

### 7.4 Review
The user can inspect:

- Contract changes
- Classifications
- Impacted files and lines
- Migration plan
- Approval event
- Branch and diff summary
- Test output
- Agent and tool timeline
- Final limitations
- Downloadable reports

---

## 8. Functional Requirements

### FR-001 — Run Creation
Create a run from:

- Old OpenAPI specification
- New OpenAPI specification
- Registered local repository
- Optional run name

Assign a unique run ID.

### FR-002 — Input Validation
Before any LLM call, validate:

- Both documents are valid OpenAPI 3.x
- Repository is inside an allowed workspace
- Repository exists and is a Git repository
- Maven wrapper or approved Maven command exists
- Repository is not used by another active run

### FR-003 — Deterministic Diff
A deterministic adapter must output normalised changes containing:

- ID
- Category
- HTTP method
- Path
- Schema
- Property
- Old value
- New value
- Raw classification
- Machine-readable evidence

The LLM must not invent or override diff facts.

### FR-004 — Internal Normalisation
External diff-library models must be converted to internal domain objects. Library-specific types must not leak into domain or application code.

### FR-005 — Classification
Classify each change as:

- `BREAKING`
- `POTENTIALLY_BREAKING`
- `NON_BREAKING`
- `UNKNOWN`

Each classification requires a machine-readable reason. Deterministic rules are authoritative; the LLM may only explain them.

### FR-006 — Repository Search
For each relevant change, search by:

- Old and new paths
- Schema names
- Old and new property names
- Removed enum values
- Relevant client method names discovered in the repository

Every match must contain:

- Relative path
- Line number or range
- Snippet
- Search term
- Relationship to the change

### FR-007 — Bounded Source Reading
The agent may inspect specific files returned by search.

Restrictions:

- Files must stay inside the repository root.
- Binary files are rejected.
- File size and output limits are enforced.
- Likely secret files are blocked.
- Only relevant line ranges should be returned where practical.

### FR-008 — Impact Assessment
For each impact, generate:

- Related API change
- Affected component
- Evidence IDs
- Expected failure mode
- Severity
- Confidence
- Recommended action
- Assumptions

No source-impact claim may exist without deterministic evidence.

### FR-009 — Migration Plan
Generate a plan before any modification.

Each item must include:

- Objective
- Expected files
- Proposed action
- Tests to add or update
- Validation command
- Risk
- Rollback approach
- Evidence references

### FR-010 — Approval
Modification is impossible until explicit approval.

Store:

- Run ID
- Plan hash
- Decision
- Timestamp

Changing the plan invalidates approval.

### FR-011 — Git Safety
Before modification, verify:

- Repository is clean
- Current branch is recorded
- Target branch does not conflict
- No untracked or modified files would be overwritten

A dirty repository blocks execution.

### FR-012 — Branch Creation
Create:

`contractguard/run-<short-run-id>`

The MVP must never automatically commit, merge, push, rebase, reset, tag, or delete branches.

### FR-013 — Patch Generation
Generate a unified diff restricted to approved plan actions and files.

The patch must be:

- Minimal
- Syntactically valid
- Free from unrelated formatting
- Free from secrets
- Checked with `git apply --check`

### FR-014 — Patch Application
The patch tool must:

1. Resolve and validate all paths.
2. Reject binary changes.
3. Reject blocked files.
4. Reject unapproved files.
5. Apply only a previously validated patch.

### FR-015 — Tests
The remediation may update existing tests or add tests. Tests must verify behaviour rather than private implementation details.

### FR-016 — Validation
For the demo, run:

`./mvnw verify`

Capture:

- Exit code
- Duration
- Standard output and error
- Test summary
- Compilation failures
- Truncation metadata

### FR-017 — Repair Attempt
If the first validation fails:

- Provide the agent only failure output and relevant approved files.
- Permit one corrective patch.
- Apply the same safety checks.
- Run validation again.
- Never perform more than one repair attempt.

### FR-018 — Run States
Supported states:

- `CREATED`
- `VALIDATING_INPUT`
- `DIFFING`
- `SEARCHING`
- `ASSESSING`
- `PLANNING`
- `AWAITING_APPROVAL`
- `REJECTED`
- `PREPARING_BRANCH`
- `PATCHING`
- `VALIDATING`
- `REPAIRING`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

Invalid transitions must be impossible.

### FR-019 — Progress Streaming
Use Server-Sent Events or equivalent.

Each event contains:

- Run ID
- Step
- Status
- Timestamp
- Human-readable message
- Optional structured metadata

### FR-020 — Reports
Generate Markdown and JSON reports containing:

- Run metadata
- Specification hashes
- Repository and branch details
- Detected changes
- Impact evidence
- Migration plan
- Approval
- Patch summary
- Validation
- Final outcome
- Limitations
- Trace IDs

### FR-021 — Run History
List previous local runs and allow their reports to be reopened.

### FR-022 — Demo Reset
Provide a documented command to restore the sample repository.

---

## 9. Agentic Workflow

Implement an explicit state graph or orchestrated state machine.

### Nodes

1. **Input Validator** — deterministic
2. **Contract Diff Tool** — deterministic
3. **Change Explainer** — LLM-assisted
4. **Consumer Investigator** — agentic tool user
5. **Migration Planner** — agentic
6. **Approval Gate** — human-controlled
7. **Implementation Agent** — agentic
8. **Patch Safety Tool** — deterministic
9. **Validation Tool** — deterministic
10. **Repair Agent** — optional, one invocation
11. **Report Generator** — deterministic templating, optional labelled summary

### Agent Rules

Agents must:

- Use schema-validated structured output.
- Cite evidence IDs.
- Separate facts, inferences, and recommendations.
- State uncertainty.
- Never claim success without tool evidence.
- Never use unregistered tools.
- Never bypass approval.
- Never expand scope silently.
- Respect step, token, time, and output limits.

---

## 10. Tool Contracts

All tools return structured results and typed failures.

### `diff_openapi`
Input: old and new specification paths  
Output: normalised changes, warnings, source hashes

### `search_repository`
Input: repository ID, query, optional glob, result limit  
Output: evidence records with path, line, snippet, and relationship

### `read_source_file`
Input: repository ID, path, optional line range  
Output: sanitised content and truncation metadata

### `inspect_git_status`
Output: branch, clean/dirty state, modified/staged/untracked files

### `create_work_branch`
Input: run ID  
Output: original branch and created branch

### `check_patch`
Input: unified diff and approved paths  
Output: validity, rejection reasons, changed paths, line summary

### `apply_patch`
Input: validated patch ID  
Output: applied paths and diff summary

### `run_validation`
Input: repository ID and approved command key  
Output: exit code, duration, build/test summary, sanitised output

---

## 11. Architecture

Use a modular monolith with hexagonal architecture.

The domain and application layers must not depend directly on:

- Spring classes
- LLM SDKs
- OpenAPI diff types
- Git implementation details
- Database details
- UI concerns

### Component View

```text
Web UI
  |
REST API + SSE
  |
Application Layer
  |-- Orchestration
  |-- Approval
  |-- Reporting
  |
Domain Layer
  |-- AnalysisRun
  |-- ApiChange
  |-- ImpactEvidence
  |-- MigrationPlan
  |-- ValidationResult
  |-- State machine
  |
Ports
  |-- OpenApiDiffPort
  |-- RepositorySearchPort
  |-- SourceReaderPort
  |-- GitWorkspacePort
  |-- PatchPort
  |-- BuildValidationPort
  |-- LlmGateway
  |-- RunRepository
  |-- EventPublisher
  |
Adapters
  |-- OpenAPI diff
  |-- Repository search
  |-- Git
  |-- Process execution
  |-- LLM provider
  |-- Embedded database
  |-- Filesystem artifacts
```

### Appropriate Patterns

Use patterns only where they reduce real coupling or model variation:

- Ports and Adapters
- State
- Strategy
- Command
- Repository
- Factory
- Policy Object
- Pipeline or Template Method only when justified

Avoid pattern-heavy abstractions with no present need.

---

## 12. Domain Model

### AnalysisRun
- `id`
- `name`
- `state`
- `createdAt`
- `updatedAt`
- `oldSpecHash`
- `newSpecHash`
- `repositoryId`
- `originalBranch`
- `workingBranch`
- `failure`
- `traceId`

### ApiChange
- `id`
- `type`
- `classification`
- `method`
- `path`
- `schema`
- `property`
- `oldValue`
- `newValue`
- `reason`
- `rawEvidence`

### ImpactEvidence
- `id`
- `apiChangeId`
- `relativePath`
- `startLine`
- `endLine`
- `snippet`
- `searchTerm`
- `relationship`
- `contentHash`

### ImpactAssessment
- `id`
- `apiChangeId`
- `component`
- `severity`
- `confidence`
- `failureMode`
- `recommendedAction`
- `assumptions`
- `evidenceIds`

### MigrationPlan
- `id`
- `version`
- `hash`
- `status`
- `items`
- `createdAt`

### Approval
- `runId`
- `planHash`
- `decision`
- `decidedAt`

### PatchArtifact
- `id`
- `runId`
- `attempt`
- `unifiedDiff`
- `changedPaths`
- `checkStatus`
- `appliedAt`

### ValidationResult
- `attempt`
- `command`
- `exitCode`
- `startedAt`
- `duration`
- `summary`
- `outputArtifact`
- `successful`

---

## 13. API

Suggested endpoints:

```text
POST   /api/runs
GET    /api/runs
GET    /api/runs/{runId}
GET    /api/runs/{runId}/events
GET    /api/runs/{runId}/changes
GET    /api/runs/{runId}/impacts
GET    /api/runs/{runId}/plan
POST   /api/runs/{runId}/approval
POST   /api/runs/{runId}/execute
GET    /api/runs/{runId}/artifacts/report.md
GET    /api/runs/{runId}/artifacts/report.json
```

Requirements:

- Versionable API DTOs
- No direct serialisation of domain entities
- Consistent problem-details errors
- Conflict response for invalid state actions
- Reconnectable SSE stream

---

## 14. UI

A polished single-page dashboard is sufficient.

### Run Setup
- Select bundled repository
- Select specifications
- Name run
- Start analysis

### Timeline
- Current and completed steps
- Tool versus LLM execution
- Duration and failure states

### Change Table
- Classification
- Change type
- Endpoint or schema
- Old and new values
- Explanation

### Impact View
- File
- Line
- Snippet
- Failure mode
- Severity
- Confidence

### Plan and Approval
- Migration actions
- Approved files
- Risks
- Approve and reject controls
- Clear mutation warning

### Validation
- Branch
- Changed files
- Patch summary
- Test status
- Build duration
- Repair indicator

### Report
- Render Markdown
- Download Markdown and JSON
- Show limitations

---

## 15. Persistence and Artifacts

Use an embedded database for structured metadata.

Use run-scoped filesystem directories for:

- Tool outputs
- Redacted LLM records
- Patches
- Validation logs
- Reports

Completed runs must survive an application restart. Resuming active runs is optional.

---

## 16. LLM Integration

Define an `LlmGateway` port.

Provide:

- One real OpenAI-compatible adapter configured by environment
- One deterministic fake adapter for tests
- Optional mock mode

Configuration includes:

- Base URL
- API key
- Model
- Timeout
- Maximum tokens
- Temperature
- Maximum workflow steps

Structured output must be schema-validated. On invalid output, retry once with validation feedback, then fail clearly.

Store prompts as versioned resources and record prompt name/version in traces.

---

## 17. Security and Safety

1. Bind locally by default.
2. Restrict repositories to configured workspace roots.
3. Reject traversal and symlink escapes.
4. Block likely secret files.
5. Redact secrets from logs and LLM context.
6. Allow only fixed executable commands.
7. Set timeouts and output limits.
8. Never execute unrestricted model-generated shell text.
9. Never push, merge, tag, deploy, or alter remotes.
10. Never modify before approval.
11. Stop on safety failure.
12. Preserve the original branch.
13. Audit every mutation.
14. Never send an entire repository to the model.
15. Send only relevant bounded context.

---

## 18. Observability

Every run includes:

- Correlation and trace IDs
- Step timings
- Tool invocations
- LLM call metadata
- Prompt version
- Token usage when available
- Validation duration
- Error classification
- Final outcome

Logs must be structured and secret-free. The UI must expose a simple trace timeline.

---

## 19. Error Handling

Provide typed failures for:

- Invalid OpenAPI
- Unsupported feature
- Repository outside workspace
- Dirty repository
- Diff failure
- Search failure
- LLM unavailable
- Invalid LLM response
- Approval mismatch
- Patch rejection
- Patch application failure
- Build timeout
- Validation failure
- Policy violation

User-facing errors must explain:

- What failed
- Whether mutation occurred
- Whether cleanup is required
- Available evidence or logs

---

## 20. Testing and Quality Gates

### Coverage Targets

Backend:

- At least 85% line coverage overall
- At least 75% branch coverage overall
- At least 90% line coverage for domain and application packages

Frontend:

- Critical flows covered
- At least 80% coverage for non-trivial UI logic

Exclusions must be explicit and justified.

### Unit Tests

Required for:

- Classification
- State transitions
- Approval invalidation
- Workspace policy
- Path validation
- Command allowlisting
- Evidence mapping
- Plan hashing
- Patch restrictions
- Repair limit
- Report generation

### Integration Tests

Required for:

- OpenAPI diff adapter
- Repository search
- Git branch creation
- Patch checking and application
- Maven validation
- Persistence
- API conflicts
- SSE events

### Agent Tests

Use a fake LLM to verify:

- Tool selection
- Structured output validation
- Evidence enforcement
- Approval pause/resume
- Retry limits
- Repair limits
- Unregistered-tool rejection

No automated test may call a live external LLM.

### End-to-End

Provide a test or script that:

1. Resets the sample.
2. Creates a run.
3. Detects expected changes.
4. Produces expected evidence.
5. Approves the plan.
6. Applies remediation.
7. Runs validation.
8. Confirms success and report creation.

### Architecture Tests

Enforce:

- Domain does not depend on frameworks or adapters.
- Application depends only on domain and ports.
- Adapters implement ports.
- API does not bypass application services.

### CI

Include:

- Compilation
- Unit and integration tests
- Coverage verification
- Static analysis
- Formatting/style check
- Frontend lint
- Frontend tests
- Frontend production build

---

## 21. Repository Structure

```text
contractguard/
├── README.md
├── docs/
│   ├── specification.md
│   ├── architecture.md
│   ├── roadmap.md
│   ├── demo-script.md
│   ├── screenshots/
│   └── adr/
├── backend/
├── frontend/
├── samples/
│   ├── openapi/
│   └── customer-consumer/
├── scripts/
│   ├── reset-demo.sh
│   └── run-demo.sh
├── docker-compose.yml
└── .github/workflows/ci.yml
```

---

## 22. Delivery Milestones

### Milestone 1
- Skeleton and CI
- Domain model
- State machine
- Sample contracts and consumer

### Milestone 2
- OpenAPI diff
- Normalisation
- Classification
- Tests

### Milestone 3
- Workspace policy
- Search and file reading
- Evidence collection
- Integration tests

### Milestone 4
- LLM abstraction
- Impact assessment
- Migration plan
- Approval API

### Milestone 5
- Git safety
- Branch creation
- Patch validation/application
- Maven validation
- Repair flow

### Milestone 6
- UI and SSE
- Reports
- Observability
- Redaction

### Milestone 7
- End-to-end test
- Coverage and static analysis
- Architecture tests
- Documentation
- Demo script
- Cleanup

Reduce optional scope rather than sacrificing safety, tests, or core completeness.

---

## 23. Acceptance Criteria

The MVP is accepted only when:

1. It starts locally from documented commands.
2. The bundled specifications load.
3. All four seeded changes are detected.
4. Classifications match expectations.
5. Known impacted files and lines are found.
6. Impact assessments cite evidence.
7. The plan lists files, tests, risks, and validation.
8. Modification is impossible before approval.
9. A dirty repository blocks execution.
10. Approval creates a dedicated branch.
11. Patches are checked before application.
12. Only approved files are changed.
13. `./mvnw verify` succeeds after remediation.
14. Reports contain all required sections.
15. The UI displays progress and outcome.
16. Coverage thresholds pass.
17. CI passes from a clean checkout.
18. Tests require no live LLM.
19. Logs contain no secrets.
20. No remote Git action occurs.
21. No fake success path, disabled test, unfinished placeholder, or misleading result exists in the demo flow.

---

## 24. Definition of Done

A feature is done only when:

- Implemented end to end
- Tested at the appropriate layers
- Error cases are covered
- Public interfaces are documented
- Observability is present
- Security policies are enforced
- Static analysis passes
- CI passes
- Documentation matches behaviour
- No relevant TODO, stub, dead code, or hard-coded success response remains

---

## 25. Stretch Goals

Only after the MVP is complete:

1. React consumer analysis
2. Gradle support
3. Remote read-only repository import
4. Draft pull-request generation
5. Pact test generation
6. Additional OpenAPI change categories
7. Multi-repository analysis
8. Cost/token dashboard
9. Local Ollama adapter
10. Interrupted-run resume
11. Jira or Confluence export
12. Semantic code search

---

## 26. Engineering Principles

- Prefer deterministic tools for facts.
- Use the LLM for bounded reasoning, investigation, planning, and code proposals.
- Human approval must be enforced by backend state.
- Every source-impact claim requires evidence.
- Every success claim requires tool output.
- Keep scope narrow and implementation complete.
- Favour clear code over clever code.
- Use patterns to express real boundaries, not to demonstrate vocabulary.
- Treat tests, traceability, and safety as product features.
