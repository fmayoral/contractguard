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

## 9. Test remote repositories and publishing (FR-027)

There is no dashboard UI for this yet (see "Known limitations" in the root
README) — everything below is `curl` against the running backend. You need a
real GitHub repository you control and a personal access token with `repo`
scope (a small throwaway repo is ideal, since ContractGuard will open a real
draft PR against it).

1. Generate a master key for encrypting stored credentials and restart the
   backend with it set (it can also just be exported before step 3 of
   section 3 above):

   ```bash
   export CONTRACTGUARD_CREDENTIAL_KEY=$(openssl rand -base64 32)
   ```

2. Register the repository:

   ```bash
   curl -s -X POST http://127.0.0.1:7080/api/repositories/remote \
     -H 'Content-Type: application/json' \
     -d '{"repositoryId":"my-remote-consumer",
          "cloneUrl":"https://github.com/<you>/<repo>",
          "defaultBranch":"main","token":"ghp_..."}' | jq
   ```

   Expect `201` with `{repositoryId, owner, name, defaultBranch, registeredAt}`
   — the token itself is never echoed back or logged.

3. Create a run against `my-remote-consumer` exactly as in section 5 (`POST
   /api/runs`, or once the frontend supports remote repos, the **New
   analysis run** picker). The first request triggers a clone into
   `storage.directory/remote-cache/my-remote-consumer/`; watch the backend
   log for `Migrating schema` / clone activity, or just check the directory
   appears on disk.
4. Approve and execute as in section 6. The run should reach `SUCCEEDED`.
5. Publish:

   ```bash
   curl -s -X POST http://127.0.0.1:7080/api/runs/<runId>/publish | jq
   ```

   Poll `GET /api/runs/<runId>` until `state` is `PUBLISHED`; the response
   includes `pullRequestUrl`. Open it — it should be a **draft** PR with the
   detected changes and evidence linked back into your repository at the
   remediation branch.
6. Failure path worth exercising: revoke the token (or use one without
   `repo` scope) and publish again — expect `state: PUBLISH_FAILED` and a
   `publish`/`FAILED` event in the timeline; re-running `POST
   .../publish` with a corrected token should succeed without re-running
   analysis or execution.

<!-- TODO(fernando): once the frontend gets a remote-repository registration
     form and a Publish button (see README "Known limitations"), capture and
     add screenshots here, following the docs/screenshots/<n>-<slug>.png
     convention used in the root README:
       - docs/screenshots/6-register-remote-repository.png (registration form)
       - docs/screenshots/7-publish-and-pull-request.png (Publish button +
         resulting pullRequestUrl / draft PR link in the run detail view)
     Until then this section stays curl-only. -->

## Troubleshooting

| Symptom | Fix |
|---|---|
| `No repositories found` in the UI | Run `scripts/reset-demo.sh` |
| Port 7080 already in use | Start with `--server.port=<free port>` and set the Vite proxy target in `frontend/vite.config.ts` accordingly. 8080 is avoided by default (NVIDIA Broadcast and similar tools squat on it); on Windows also check `netsh interface ipv4 show excludedportrange protocol=tcp` — Hyper-V reserves ranges that fail binds with "port in use" while nothing is listening |
| Validation timeout on first run | The consumer's first `mvnw verify` downloads dependencies; re-run, or raise `contractguard.validation.timeout` |
| Run failed with `REPOSITORY_BUSY` | Another run is active on the repository; wait or restart the backend |
| Stale demo state | Re-run `scripts/reset-demo.sh` |
| `CREDENTIAL_KEY_NOT_CONFIGURED` registering a remote repository | Set `CONTRACTGUARD_CREDENTIAL_KEY` (base64, 32 bytes — `openssl rand -base64 32`) before registering |
| `REMOTE_REPOSITORY_NOT_REGISTERED` on publish | The run's `repositoryId` was never registered via `POST /api/repositories/remote`; local-workspace runs cannot be published |
| Publish fails with `REMOTE_GIT_FAILURE` | Check the token has `repo` scope and the default branch name is correct; the run moves to `PUBLISH_FAILED` and can be retried with `POST .../publish` once fixed |
