# ContractGuard AI

Local-first agentic tool that compares two OpenAPI specifications, classifies
contract changes, finds affected usages in a Java consumer repository with
file-and-line evidence, produces a migration plan, pauses for human approval,
applies a minimal patch on an isolated Git branch, runs the consumer test
suite, and produces auditable Markdown and JSON reports.

> Status: under active development. See
> [docs/implementation-plan.md](docs/implementation-plan.md) for the build
> plan and [docs/contractguard-requirements.md](docs/contractguard-requirements.md)
> for the full specification.

## Prerequisites

- Java 21 (JDK)
- Git on `PATH`
- Node.js 20+ (frontend)
- Internet access for the first Maven/npm dependency download

## Quick start

```bash
# 1. Materialise the demo consumer repository into ./workspace
scripts/reset-demo.sh          # PowerShell: scripts/reset-demo.ps1

# 2. Start the backend (http://localhost:8080)
backend/mvnw -f backend spring-boot:run

# 3. Start the frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

Then open the dashboard, pick the bundled repository and the two sample
specifications under `samples/openapi/`, and start a run. The default
configuration uses the deterministic mock LLM, so no API key is required.
See [docs/demo-script.md](docs/demo-script.md) for the guided walkthrough.

## Repository layout

```
backend/    Spring Boot 3 / Java 21 modular monolith (hexagonal)
frontend/   React + TypeScript dashboard
samples/    Bundled OpenAPI specs and the Java consumer source template
scripts/    Demo reset / run helpers
docs/       Requirements, architecture, ADRs, demo script
```

## Documentation

- [Architecture](docs/architecture.md)
- [Implementation plan](docs/implementation-plan.md)
- [Demo script](docs/demo-script.md)
- [ADRs](docs/adr/)
