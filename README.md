# ContractGuard AI

Local-first agentic tool that compares two OpenAPI specifications, classifies
contract changes, finds affected usages in a Java consumer repository with
file-and-line evidence, produces a migration plan, pauses for human approval,
applies a minimal patch on an isolated Git branch, runs the consumer test
suite (with at most one bounded repair attempt), and produces auditable
Markdown and JSON reports.

Deterministic tools are authoritative for every fact — diffs, evidence, Git
state, patches, test results. The LLM explains, investigates within a bounded
tool loop, plans and proposes code; everything it produces is schema-validated
and enforced by backend state. The default **mock mode** uses a deterministic
scripted gateway, so the whole demo runs without any API key or network.

## How a run flows

A run walks a strict state machine from `CREATED` to a terminal state; every
step is streamed live to the dashboard timeline and persisted for the final
report. The dashboard ships with dark and light themes (toggle in the
sidebar).

### 1 — Analyse: diff, classify, explain

Pick the consumer repository and the old/new specifications, then start the
analysis (a local workspace checkout, or a registered GitHub repository —
see [Remote repositories](#remote-repositories) below). The backend
validates inputs, computes a deterministic diff of the two specs, classifies
each change (`BREAKING` / `NON_BREAKING` / `POTENTIALLY_BREAKING` /
`UNKNOWN`) by fixed policy, and has the LLM explain the consumer consequences
of each fact it is given — it cannot add or remove changes. The timeline
shows each step as it happens, tagged by who acted (tool, LLM, human,
system).

![Timeline and classified contract changes](docs/screenshots/1-timeline-and-contract-changes.png)

### 2 — Investigate: evidence-backed consumer impact

Deterministic text search collects file-and-line evidence for every breaking
change, then the impact investigator (a bounded agent with exactly two tools:
`search_repository` and `read_source_file`, limited by a step budget) assesses
severity and failure mode per affected component. Every assessment must cite
collected evidence IDs — uncited claims are rejected by the backend.

![Consumer impact with file-and-line evidence](docs/screenshots/2-consumer-impact.png)

### 3 — Plan and approve: the human gate

The migration planner turns assessments into a versioned plan: objectives,
exact files, tests to update, validation command, risk and rollback per item.
Nothing has touched the repository yet. Approval is recorded against the exact
plan hash — if the plan changes, the approval is void; a rejection ends the
run with the repository untouched.

![Migration plan awaiting approval](docs/screenshots/3-migration-plan-and-approval.png)

### 4 — Remediate and validate: isolated branch, checked patch

After approval, ContractGuard verifies the repository is clean, creates
`contractguard/run-<id>` off the current branch, and applies a
backend-computed patch restricted to the approved files (verified with
`git apply --check`, secret-scanned, never touching the original branch). The
consumer's own test suite then validates the result; one bounded repair
attempt is allowed after a failed validation. The patch is browsable per file
with git-style line numbers and syntax highlighting.

![Applied patch with per-file diff and passing validation](docs/screenshots/4-remediation-and-validation.png)

### 5 — Report: the audit trail

Every run ends with deterministic Markdown and JSON reports rendered from
persisted state — spec hashes, classified changes, evidence, assessments, the
approved plan and decision, patches, validation output, honest limitations and
the full event trace.

![Auditable run report](docs/screenshots/5-report.png)

### 6 — Publish: push and open a draft pull request

Once remediation succeeds against a registered GitHub repository, a
**Publish** card offers a button that commits the working branch under a
fixed `ContractGuard` bot identity, pushes it and opens a **draft** pull
request whose body embeds the plan hash and links evidence back into the
target repository — a distinct, explicit action from Execute, never
automatic. The PR link then shows directly in the card; a failed publish can
be retried once the underlying issue (e.g. an expired token) is fixed.

<!-- TODO(fernando): capture docs/screenshots/6-publish-and-pull-request.png
     (Publish card + resulting draft-PR link) once you have a registered
     repository to publish against — see the "Remote repositories" section
     and docs/demo-script.md §9 for the walkthrough. -->

## Prerequisites

- Java 21 (JDK)
- Git on `PATH`
- Node.js 20+ (frontend)
- Internet access for the first Maven/npm dependency download

## Quick start

```bash
scripts/run-demo.sh              # PowerShell: scripts/run-demo.ps1
```

That resets the demo repository, builds and starts the backend
(http://127.0.0.1:7080) and the dashboard (http://localhost:5173).
Or run the steps manually:

```bash
scripts/reset-demo.sh                       # materialise workspace/customer-consumer
cd backend && ./mvnw spring-boot:run        # backend
cd frontend && npm install && npm run dev   # dashboard
```

Containerised alternative: `docker compose up --build`, then open
<http://localhost:5173>.

Follow [docs/demo-script.md](docs/demo-script.md) for the guided walkthrough
of the bundled scenario (endpoint rename, field rename, enum value removal,
optional field addition).

## CI gate (headless CLI)

The same jar runs as an analysis-only gate for pull requests — no server, no
remediation, mock LLM by default so it needs no key:

```bash
java -jar contractguard-backend.jar \
  --spring.profiles.active=cli \
  --contractguard.workspace.roots=/checkouts \
  --contractguard.specs.directory=/specs \
  --contractguard.cli.repository=consumer-repo \
  --contractguard.cli.old-spec=api-main.yaml \
  --contractguard.cli.new-spec=api-proposed.yaml \
  --contractguard.cli.fail-on=breaking \
  --contractguard.cli.output=contractguard-report.json
```

Exit codes: `0` no gated changes, `1` analysis failed, `2` gated changes
found. `fail-on` accepts `breaking` (default), `potentially-breaking` (also
gates potentially-breaking and unanalysed change categories) or `none`
(report only). Ready-made wrappers live in
[integrations/github-action](integrations/github-action/action.yml) and
[integrations/gitlab](integrations/gitlab/contractguard-gate.yml); the
consumer checkout must be a Git repository with a Maven wrapper.

## Remote repositories

Consumers do not need a pre-existing local checkout. In the dashboard,
expand **+ Register a GitHub repository** under **New analysis run**, and
supply a clone URL, default branch and a personal access token (`repo`
scope) — the server needs `CONTRACTGUARD_CREDENTIAL_KEY` set (a base64
256-bit key, e.g. `openssl rand -base64 32`) before it will accept one. The
registered repository then appears in the **Consumer repository** picker
under "Registered GitHub repositories", alongside any local workspace repos.
Or register it directly over the API:

```bash
export CONTRACTGUARD_CREDENTIAL_KEY=$(openssl rand -base64 32)  # server-side master key
curl -X POST http://localhost:7080/api/repositories/remote \
  -H 'Content-Type: application/json' \
  -d '{"repositoryId":"customer-consumer",
       "cloneUrl":"https://github.com/acme/widgets",
       "defaultBranch":"main","token":"ghp_..."}'
```

Creating a run against a registered repository clones (or refreshes) it into
`storage.directory/remote-cache/` automatically — everything downstream
(diff, evidence search, execution) works exactly as it does for a local
workspace repository. Once a run reaches `SUCCEEDED`, a **Publish** card
offers a button that commits, pushes the working branch and opens a draft
pull request, then shows the PR link; nothing is ever pushed without that
explicit click (or the equivalent `POST /api/runs/{id}/publish` call).
See [ADR-0007](docs/adr/0007-remote-repository-access.md) for the credential
and transport design, and
[docs/demo-script.md §9](docs/demo-script.md#9-test-remote-repositories-and-publishing-fr-027)
for a full walkthrough against a real GitHub repository.

## Using a real LLM

Automated tests and the default demo never call a live model. To run the
agents against an OpenAI-compatible endpoint:

```bash
export CONTRACTGUARD_LLM_API_KEY=...        # never committed, redacted from logs
cd backend
./mvnw spring-boot:run -Dspring-boot.run.arguments=--contractguard.llm.provider=openai
```

## Configuration reference

All settings live under `contractguard.*` in
[backend/src/main/resources/application.yml](backend/src/main/resources/application.yml):

| Key | Default | Purpose |
|---|---|---|
| `workspace.roots` | `../workspace` | Directories whose direct child Git repositories are analysable |
| `storage.directory` | `../data` | Embedded database + run artifacts |
| `storage.retention-days` | `0` (keep forever) | Purge finished runs, their events and artifacts after N days |
| `specs.directory` | `../samples/openapi` | Specifications offered in run setup |
| `llm.provider` | `mock` | `mock` (deterministic) or `openai` |
| `llm.base-url` / `llm.api-key` / `llm.model` | env-driven | OpenAI-compatible endpoint |
| `llm.timeout`, `llm.max-tokens`, `llm.temperature` | 60s / 4096 / 0.0 | Call bounds |
| `llm.max-workflow-steps` | 20 | Investigator tool-loop budget |
| `validation.command-key` | `maven-verify` | Only allow-listed validation command |
| `validation.timeout` / `validation.max-output-bytes` | 15m / 1MB | Build bounds |
| `remote.credential-key` | env-driven (`CONTRACTGUARD_CREDENTIAL_KEY`) | AES-256-GCM master key encrypting remote-repository tokens at rest |

### Database

The default is embedded H2 (file mode) — zero setup. For server deployments
run with the `postgres` profile and point it at a PostgreSQL 15+ instance:

```bash
export CONTRACTGUARD_DB_URL=jdbc:postgresql://db:5432/contractguard
export CONTRACTGUARD_DB_USER=contractguard
export CONTRACTGUARD_DB_PASSWORD=...
java -jar contractguard-backend.jar --spring.profiles.active=postgres
```

The schema is managed by Flyway on both engines (vendor-specific migrations
under `db/migration/`); databases created before Flyway are baselined in
place on first start.

## Verification

```bash
# Backend: unit, adapter, API, agent and architecture tests + coverage,
# Checkstyle and SpotBugs (all build-breaking)
cd backend && ./mvnw verify

# Backend incl. the full end-to-end demo test (real git + real mvnw verify)
cd backend && ./mvnw verify -Pe2e

# Persistence adapters against real PostgreSQL (requires Docker)
cd backend && ./mvnw test -Ppg

# Frontend: lint, tests with coverage, production build
cd frontend && npm ci && npm run lint && npm run test:coverage && npm run build
```

CI (`.github/workflows/ci.yml`) runs all of the above from a clean checkout.

## Repository layout

```
backend/       Spring Boot 3 / Java 21 modular monolith (hexagonal, ArchUnit-enforced)
frontend/      React + TypeScript dashboard (Vite, Vitest)
samples/       Bundled OpenAPI specs and the Java consumer source template
scripts/       reset-demo / run-demo helpers
integrations/  CI gate wrappers (GitHub Action, GitLab template)
docs/          Specification, architecture, ADRs, demo script, roadmap
```

## Safety guarantees

- No source modification before a recorded human approval of the exact plan
  hash; a changed plan invalidates approval.
- Repository access confined to workspace roots; symlink escapes and likely
  secret files rejected; vendored build tooling never becomes evidence.
- Patches are computed by the backend, verified with `git apply --check`,
  restricted to approved files, and rejected if credential-shaped content
  appears. All mutations happen on `contractguard/run-<id>`; the original
  branch is never touched.
- Remote operations (clone, fetch, push, opening a pull request) only exist
  for repositories explicitly registered via `POST /api/repositories/remote`,
  and pushing/opening a PR is a separate, explicit `POST
  /runs/{id}/publish` call — never automatic. Credentials are AES-256-GCM
  encrypted at rest and never appear in a process argument list (ADR-0007).
- Process execution uses fixed argument arrays with timeouts and output caps;
  model output is never executed.
- At most one repair attempt after a failed validation, enforced structurally.

## Known limitations

- Automated remediation covers endpoint renames, property renames and enum
  value removals; other breaking categories are reported with evidence but
  migrated manually.
- Evidence collection is text-search based; dynamically constructed
  references can be missed.
- One repository, Maven builds and OpenAPI 3.x only (see the specification, §4).
- Resuming a run interrupted mid-flight is not supported; such runs are
  finalised as FAILED on restart with an explanatory message.
- Remote repository support (FR-027) is GitHub-only, HTTPS-token-only: no
  GitLab/Bitbucket and no SSH yet. The draft pull request links evidence back
  to GitHub blob URLs rather than a ContractGuard report link, since the
  server binds to loopback only and has no public URL by default.
- The dashboard's registration form and Publish button assume one operator
  per instance; there is no per-user credential scoping or role separation
  yet (see roadmap FR-024, Authentication and Authorisation).

## Documentation

- [Architecture](docs/architecture.md) · [ADRs](docs/adr/)
- [Demo script & troubleshooting](docs/demo-script.md)
- [Specification](docs/specification.md)
- [Roadmap](docs/roadmap.md)
