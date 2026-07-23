# ContractGuard AI — Configuration Reference

Advanced setup beyond the zero-config demo: sandboxed validation,
observability, a real LLM provider, the full settings table, and database
options. For everyday feature usage (running an analysis, remote
repositories, spec sources, the audit trail), see the [Usage Guide](usage.md).
For Docker-specific configuration and troubleshooting, see
[Docker deployment](deployment.md).

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
See [ADR-0010](adr/0010-sandboxed-validation.md) for the full design,
including a `ProcessRunner` timeout bug this work found and fixed along the
way.

## Observability

Every named pipeline step (`diff`, `search`, `assessment`, `planning`,
`branch`, `patch`, `validation`, `publish-*`, each LLM call) is wrapped in a
[Micrometer `Observation`](https://docs.micrometer.io/micrometer/reference/observation.html),
which produces both an OpenTelemetry span and a Prometheus timer metric from
the same instrumentation point — see
[ADR-0011](adr/0011-observability.md).

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

The [Docker deployment](deployment.md#4-viewing-traces-metrics-and-health-fr-029)
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
[backend/src/main/resources/application.yml](../backend/src/main/resources/application.yml):

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
| `remote.credential-key` | env-driven (`CONTRACTGUARD_CREDENTIAL_KEY`) | AES-256-GCM master key encrypting remote-repository *and* spec-source tokens at rest; only required if you register one with a token (public spec sources need none — see [Specification sources](usage.md#specification-sources)) |
| `spring.servlet.multipart.max-file-size` / `.max-request-size` | `2MB` / `2MB` | Uploaded specification file size cap |
| `audit.default-principal` | `local-operator` | Attributed to every audit entry until FR-024 adds real authentication |
| `concurrency.max-active-runs` | `10` | System-wide bound on non-terminal runs; `0` disables the limit |
| `management.tracing.sampling.probability` | `1.0` | Fraction of spans sampled; full sampling is fine at this tool's scale |
| `management.otlp.tracing.endpoint` | unset (log export only) | Point at a real OpenTelemetry collector; see [Observability](#observability) |

## Database

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
