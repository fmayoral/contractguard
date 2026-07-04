# ContractGuard AI — Demo Script

A guided walkthrough of the bundled scenario: the Customer API renames an
endpoint and a field, retires an enum value, and adds an optional field; the
bundled Spring Boot consumer is analysed, a migration plan is approved, and
ContractGuard remediates the consumer on an isolated branch until
`mvnw verify` passes.

## 1. Prerequisites

- Java 21 on `PATH` (or `JAVA_HOME` pointing at a JDK 21)
- Git on `PATH`
- Node.js 20+ (for the dashboard)
- First run downloads Maven/npm dependencies; allow a few minutes.

## 2. Reset the demo repository

```bash
scripts/reset-demo.sh          # PowerShell: scripts/reset-demo.ps1
```

This copies `samples/customer-consumer` into `workspace/customer-consumer`
and creates a fresh Git repository there with a single commit. Re-run it any
time to discard demo mutations (FR-022).

## 3. Start the backend

```bash
cd backend
./mvnw spring-boot:run           # Windows: mvnw.cmd spring-boot:run
```

The API listens on `http://127.0.0.1:7080` (loopback only). The default LLM
provider is the deterministic mock — no API key needed. To use a real
OpenAI-compatible endpoint instead:

```bash
export CONTRACTGUARD_LLM_API_KEY=sk-...
./mvnw spring-boot:run -Dspring-boot.run.arguments=--contractguard.llm.provider=openai
```

## 4. Start the dashboard

```bash
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>.

## 5. Run the analysis

1. In **New analysis run**, keep `customer-consumer`,
   `customer-api-v1.yaml` (old) and `customer-api-v2.yaml` (new); name the
   run and press **Start analysis**.
2. Watch the timeline stream: input validation, deterministic diff, the LLM
   change explainer, repository search, the investigator's bounded tool
   calls, and planning.
3. **Contract changes** shows exactly four changes:
   `ENDPOINT_RENAMED` (breaking), `PROPERTY_RENAMED` (breaking),
   `ENUM_VALUE_REMOVED` (breaking), `PROPERTY_ADDED` (non-breaking).
4. **Consumer impact** lists the affected files with line numbers and
   snippets — client, DTO, enum, business rule, tests and README.

## 6. Approve and execute

1. Review the **Migration plan**: objectives, exact files, tests, risk,
   rollback and the plan hash. Nothing has been modified yet.
2. Press **Approve plan** (the decision is recorded against the plan hash;
   a stale hash is rejected with HTTP 409).
3. Press **Execute remediation**. ContractGuard verifies the repository is
   clean, creates `contractguard/run-<id>`, generates the patch, verifies it
   with `git apply --check`, applies it, and runs `./mvnw verify` in the
   consumer.
4. **Remediation & validation** shows the branch, the unified diff, and the
   test outcome (`BUILD SUCCESS`, 3 tests — the retired SUSPENDED scenario
   test was removed with the enum value).

## 7. Review the results

- Load the **Report** and download the Markdown/JSON artifacts.
- Inspect the consumer: `git -C workspace/customer-consumer log --oneline`
  shows the original commit only; `git -C workspace/customer-consumer diff main`
  shows the applied patch on the working branch. `main` is untouched, and no
  remote was ever contacted.

## 8. Things worth demonstrating

- **Rejection path**: start a second run and press **Reject** — the run ends
  `REJECTED` and the repository is untouched.
- **Dirty-repository guard**: edit any file in `workspace/customer-consumer`
  before pressing Execute; execution fails with `DIRTY_REPOSITORY` and a
  clear remediation hint.
- **Approval integrity**: `POST /api/runs/{id}/approval` with a wrong
  `planHash` returns HTTP 409 `APPROVAL_MISMATCH`.
- **Run history**: restart the backend — completed runs and their reports
  remain available.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `No repositories found` in the UI | Run `scripts/reset-demo.sh` |
| Port 7080 already in use | Start with `--server.port=<free port>` and set the Vite proxy target in `frontend/vite.config.ts` accordingly. 8080 is avoided by default (NVIDIA Broadcast and similar tools squat on it); on Windows also check `netsh interface ipv4 show excludedportrange protocol=tcp` — Hyper-V reserves ranges that fail binds with "port in use" while nothing is listening |
| Validation timeout on first run | The consumer's first `mvnw verify` downloads dependencies; re-run, or raise `contractguard.validation.timeout` |
| Run failed with `REPOSITORY_BUSY` | Another run is active on the repository; wait or restart the backend |
| Stale demo state | Re-run `scripts/reset-demo.sh` |
