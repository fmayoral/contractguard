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

```mermaid
flowchart LR
    A["1 · Analyse<br/>diff, classify, explain"] --> B["2 · Investigate<br/>evidence-backed impact"]
    B --> C["3 · Plan & Approve<br/>the human gate"]
    C --> D["4 · Remediate & Validate<br/>isolated branch, checked patch"]
    D --> E["5 · Report<br/>the audit trail"]
    E -.->|"registered GitHub repo only"| F["6 · Publish<br/>draft pull request"]
```

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
- Optional: a local Docker daemon, only to enable sandboxed build validation
  (`contractguard.validation.docker.enabled` — off by default, see
  [Sandboxed validation](#sandboxed-validation) below)

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

Containerised alternative — no Java/Node/Maven needed, just Docker:
`docker compose up --build`, then open <http://localhost:5173>. See
[docs/deployment.md](docs/deployment.md) for configuration (all optional)
and troubleshooting.

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

Consumers do not need a pre-existing local checkout. In the dashboard, open
**Manage sources** (next to **New analysis run**) and register a GitHub
repository there, supplying a clone URL, default branch and a personal
access token (`repo` scope) — the server needs `CONTRACTGUARD_CREDENTIAL_KEY`
set (a base64 256-bit key, e.g. `openssl rand -base64 32`) before it will
accept one. The registered repository then appears in the **Consumer
repository** picker under "Registered GitHub repositories", alongside any
local workspace repos, and can be removed again from the same **Manage
sources** modal (which also deletes its local clone cache) if it was
registered with the wrong URL or an under-scoped token. Or register it
directly over the API:

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

## Specification sources

Old/new OpenAPI specs no longer have to live in a directory the server
process can already see. The **Manage sources** modal offers three ways to
get specs in front of a run, all landing in the same picker on the **New
analysis run** form:

- **Bundled/local** — files already in `contractguard.specs.directory`, as before.
- **Upload** — pick a file from your machine (`.yaml`/`.yml`/`.json`, 2 MB
  cap); it's stored under `storage.directory/uploaded-specs/` and available
  immediately. Re-uploading the same file name replaces it.
- **A registered spec repository** — like remote consumer repositories, but
  read-only and, unlike them, **the token is optional**: a public repository
  (this project's own demo specs, for instance) needs no credential at all.
  A registered spec source is cloned/refreshed every time setup options are
  requested, so it always reflects the latest commit on its default branch.

```bash
# Public repository: no token, no CONTRACTGUARD_CREDENTIAL_KEY needed.
curl -X POST http://localhost:7080/api/spec-sources \
  -H 'Content-Type: application/json' \
  -d '{"repositoryId":"openapi-specs",
       "cloneUrl":"https://github.com/acme/openapi-specs","defaultBranch":"main"}'

# Upload a file directly (multipart):
curl -X POST http://localhost:7080/api/specs -F file=@customer-api-v2.yaml
```

Every spec offered at run creation carries a qualified ID
(`local:<name>`, `upload:<name>`, `source:<repositoryId>:<name>`) so files
with the same name from different origins never collide; a bare file name
(what the headless CLI gate has always sent) still resolves against the
local directory unchanged. See
[ADR-0012](docs/adr/0012-spec-source-repositories-and-upload.md) for the
full design.

## Audit trail

Every state transition, approval decision and repository mutation is
recorded to a separate, append-only audit log — distinct from the
operational timeline: it is never purged by the retention policy that
deletes finished runs, and there is no API route to edit or delete an entry.

```bash
curl 'http://localhost:7080/api/audit?runId=<runId>'          # one run
curl 'http://localhost:7080/api/audit?repositoryId=<repoId>'  # one repository, across runs
curl 'http://localhost:7080/api/audit'                        # everything
```

`principal` is a single operator-configured placeholder
(`contractguard.audit.default-principal`) until FR-024 (authentication)
lands — see [ADR-0008](docs/adr/0008-audit-trail.md).

## Sandboxed validation

By default the consumer's own build (`mvnw verify`) runs directly on the
host, exactly as in the MVP. Set `contractguard.validation.docker.enabled`
to `true` (and start Docker) to run it in a resource-limited, network-
isolated container instead:

```bash
export CONTRACTGUARD_VALIDATION_DOCKER_ENABLED=true
cd backend
./mvnw spring-boot:run -Dspring-boot.run.arguments=--contractguard.validation.docker.enabled=true
```

No network egress by default — instead of opening a package-registry
exception, the host's own `~/.m2` is mounted **read-only** into the
container, so previously-resolved dependencies (and the Maven wrapper's own
cached distribution) are available offline. A repository whose dependencies
were never resolved on this host will still fail without network access;
set `validation.docker.network-enabled: true` if that's not acceptable for
a given deployment. Memory, CPU and wall-clock are all bounded
(`validation.docker.memory` / `.cpus` / `validation.timeout`); a timed-out
sandboxed build is killed and its container removed, never left running.
See [ADR-0010](docs/adr/0010-sandboxed-validation.md) for the full design,
including a `ProcessRunner` timeout bug this work found and fixed along the
way.

## Observability

Every named pipeline step (`diff`, `search`, `assessment`, `planning`,
`branch`, `patch`, `validation`, `publish-*`, each LLM call) is wrapped in a
[Micrometer `Observation`](https://docs.micrometer.io/micrometer/reference/observation.html),
which produces both an OpenTelemetry span and a Prometheus timer metric from
the same instrumentation point — see
[ADR-0011](docs/adr/0011-observability.md).

```bash
curl 'http://localhost:7080/actuator/health'            # overall status
curl 'http://localhost:7080/actuator/health/readiness'   # orchestration probe
curl 'http://localhost:7080/actuator/prometheus'         # scrape target
```

Spans are exported to the application log by default when run this way — no
collector needed to see tracing working. Point at a real backend (Jaeger,
Tempo, etc.) with:

```bash
export MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces
```

The [Docker deployment](docs/deployment.md#4-viewing-traces-metrics-and-health-fr-029)
bundles a Jaeger UI and points traces at it by default — nothing to
configure there; open <http://localhost:16686> after running an analysis.

`contractguard.runs.transitions{to_state=...}` counts runs by state, derived
from the audit trail; `contractguard.llm.tokens{prompt=...,type=...}`
tracks prompt/completion token volume per prompt. Structured JSON logging
(correlating log lines to the same trace ID) is not yet built — it needs
Spring Boot 3.4, and this project is pinned to 3.3.5; see ADR-0011 for why
that was deferred rather than half-built.

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
| `validation.docker.enabled` | `false` | Run validation in a sandboxed container instead of on the host |
| `validation.docker.image` / `.memory` / `.cpus` | `eclipse-temurin:21-jdk` / `2g` / `2` | Sandbox resource limits |
| `validation.docker.network-enabled` | `false` | Off by default; see [Sandboxed validation](#sandboxed-validation) |
| `validation.docker.maven-local-repo` | `${user.home}/.m2` | Mounted read-only so offline builds still resolve cached dependencies |
| `remote.credential-key` | env-driven (`CONTRACTGUARD_CREDENTIAL_KEY`) | AES-256-GCM master key encrypting remote-repository *and* spec-source tokens at rest; only required if you register one with a token (public spec sources need none — see [Specification sources](#specification-sources)) |
| `spring.servlet.multipart.max-file-size` / `.max-request-size` | `2MB` / `2MB` | Uploaded specification file size cap |
| `audit.default-principal` | `local-operator` | Attributed to every audit entry until FR-024 adds real authentication |
| `concurrency.max-active-runs` | `10` | System-wide bound on non-terminal runs; `0` disables the limit |
| `management.tracing.sampling.probability` | `1.0` | Fraction of spans sampled; full sampling is fine at this tool's scale |
| `management.otlp.tracing.endpoint` | unset (log export only) | Point at a real OpenTelemetry collector; see [Observability](#observability) |

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

# Sandboxed-validation adapter against real docker run (requires Docker)
cd backend && ./mvnw test -Pdocker

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
- Every state transition, approval decision and repository mutation is
  recorded to an append-only audit log with no API route to edit or delete
  an entry; unlike the operational timeline, it is never purged by the
  retention policy (ADR-0008).
- A per-repository lock closes the race where two simultaneous requests
  could both pass the "is this repository busy?" check before either run
  was written; the system-wide run queue is explicitly bounded
  (`concurrency.max-active-runs`) rather than relying on an unbounded
  thread-pool queue. A run interrupted by a restart while still `CREATED`
  is safely resumed from scratch; every other interrupted state is
  finalised as FAILED with a clean message rather than resumed mid-mutation
  (ADR-0009).
- Consumer builds can run in a resource-limited, network-isolated container
  instead of on the host (opt-in, `validation.docker.enabled`); a timed-out
  sandboxed build is killed and its container removed rather than left
  running (ADR-0010).

## Known limitations

- Automated remediation covers endpoint renames, property renames and enum
  value removals; other breaking categories are reported with evidence but
  migrated manually.
- Evidence collection is text-search based; dynamically constructed
  references can be missed.
- One repository, Maven builds and OpenAPI 3.x only (see the specification, §4).
- Resuming a run interrupted mid-flight is supported only for runs still
  `CREATED` (FR-032); anything past that point (mid-diff, mid-patch,
  mid-publish, ...) is finalised as FAILED on restart with an explanatory
  message rather than resumed, since the state machine's transitions are
  intentionally one-shot (ADR-0009).
- Remote repository support (FR-027) is GitHub-only, HTTPS-token-only: no
  GitLab/Bitbucket and no SSH yet. The draft pull request links evidence back
  to GitHub blob URLs rather than a ContractGuard report link, since the
  server binds to loopback only and has no public URL by default.
- The dashboard's registration form and Publish button assume one operator
  per instance; there is no per-user credential scoping or role separation
  yet (see roadmap FR-024, Authentication and Authorisation).
- The audit trail (FR-025) attributes every entry to one configured
  principal, not a real authenticated identity, for the same reason.
- Sandboxed validation (FR-030) has no `--user` mapping into the container,
  so build output may end up root-owned on a Linux host; and network access
  is genuinely off by default, so a repository whose dependencies were
  never resolved on the host will fail rather than fetch them.
- Observability (FR-029) ships tracing, metrics and health endpoints, but
  not structured JSON logs — that needs Spring Boot 3.4, and this project's
  parent POM is pinned to 3.3.5 (ADR-0011). Trace export defaults to the
  application log; a real collector needs `management.otlp.tracing.endpoint`
  configured.
- Registered repositories, spec sources and uploaded specifications (FR-044)
  can be deregistered/deleted, but the registry is still process-wide, not
  scoped to any user — the same single-operator trust model as everything
  else pending FR-024 (ADR-0012).

## Documentation

- [Architecture](docs/architecture.md) · [ADRs](docs/adr/)
- [Docker deployment](docs/deployment.md)
- [Demo script & troubleshooting](docs/demo-script.md)
- [Specification](docs/specification.md)
- [Roadmap](docs/roadmap.md)
