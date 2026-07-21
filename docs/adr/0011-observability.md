# ADR-0011: Observability via a port, Micrometer Observation as the adapter

- Status: accepted
- Date: 2026-07-21

## Context

FR-029 asks for OpenTelemetry traces spanning each pipeline node, LLM calls
annotated with token counts, Prometheus metrics (runs by state, durations,
failure categories, LLM spend), structured JSON logs correlated by the
existing `traceId`, and health/readiness endpoints. Before this work, a run
was only observable by reading its own event log after the fact — nothing
about timing, failure rates or LLM cost was visible from outside the
process, and there was no signal an orchestrator could poll to know the
service was healthy.

## Decisions

### 1. A new `ObservabilityPort`, not a direct Micrometer dependency in application code

`HexagonalArchitectureTest.applicationDependsOnlyOnDomainAndJdk` restricts
`application` to `application`, `domain` and `java..` — Micrometer isn't on
that allow-list today, and extending the allow-list for a single library
would blur the one hexagonal invariant this codebase enforces by build
failure, not convention. So `application.port.ObservabilityPort` follows the
existing pattern (`AuditTrailPort`, `RunEventLog`, `LlmGateway`): a small,
framework-free interface — `startSpan(name, tags)` returning an
`AutoCloseable` `SpanHandle`, plus `recordLlmUsage(promptName, promptTokens,
completionTokens)` — that `AnalysisPipeline`, `ExecutionService`,
`PublishService` and `LlmJsonClient` depend on and a new
`adapter.observability.MicrometerObservability` implements. Tests use a
`NoOpObservability` double, the same shape as `InMemoryAuditTrail`.

### 2. Micrometer's `Observation` API, not the raw OpenTelemetry SDK

`Observation.start()`/`.stop()` produces a Prometheus `Timer` metric *and*
an OpenTelemetry span from one instrumentation call — durations and traces
for the same unit of work come from the same line of code instead of two
parallel instrumentation efforts drifting apart. `MicrometerObservability`
is the only class that imports `io.micrometer.observation.*`; the adapter,
not the tool, decides whether a span becomes a trace, a metric, both or
neither depending on what's on the classpath and configured.

### 3. Span tags are always high-cardinality (trace-only), never metric labels

A run ID is unbounded — tagging a Prometheus `Timer` with it would grow that
metric's cardinality forever. `startSpan`'s tags are recorded via
`Observation.highCardinalityKeyValue`, which reaches the trace but is
excluded from the metric dimensions; the per-step `Timer` (e.g.
`contractguard.diff`) stays name-keyed. The one deliberate exception is
`MetricsRecordingAuditTrail`'s `to_state` label on
`contractguard.runs.transitions` — `RunState` is a small closed enum, so its
cardinality cannot grow, unlike a run ID.

### 4. Spans nest by wrapping named pipeline-node methods, reusing existing step boundaries

Every step that already had a name in the event log
(`input-validation`/`diff`/`change-explainer`/`search`/`assessment`/
`planning` in `AnalysisPipeline`; `branch`/`patch`/`validation` in
`ExecutionService`; `publish-commit`/`publish-push`/`publish-pr` in
`PublishService`, newly extracted into their own methods for this) gets its
body wrapped in `try (SpanHandle span = startSpan(run, name)) { ... }`. Each
of the three async entry points (`analyse`, `execute`, `publish`) is
*also* wrapped, so `Observation`'s automatic current-observation propagation
nests the step spans under the phase span without threading a parent
reference through every method signature. `LlmJsonClient` wraps each
`gateway.complete()` attempt in its own span (`llm.<promptName>`, tagged
with `attempt`) and calls `recordLlmUsage` right after, since it is the
single chokepoint every LLM-calling agent already goes through — this
avoids repeating the same wrapping in `ChangeExplainer`, `ImpactInvestigator`,
`MigrationPlanner` and `ImplementationAgent` individually.

Deliberately not attempted: a single span covering an entire run's
wall-clock lifetime (`CREATED` through a terminal state). Runs pause for
human approval in between the analysis and execution phases, sometimes for
a long time; a "span" spanning that idle wait would misrepresent what
tracing is for. Correlating the three phase spans of one run is done via the
shared `runId`/`repositoryId` tags, not by a single enclosing span.

### 5. `runs by state` metric reuses the audit trail as its chokepoint, not a new call in every service

`MetricsRecordingAuditTrail` decorates the existing `AuditTrailPort`:
whenever a `STATE_TRANSITION` entry is recorded (already the single place
every state change flows through, via `AuditTrailService.recordTransition`
from FR-025), it increments `contractguard.runs.transitions{to_state=...}`.
No application-service code changed for this metric — the decorator lives
entirely in `adapter.observability`, parsing the entry's existing
`"FROM -> TO"` detail string rather than adding a new port method.

### 6. Spans export to the log by default; no OTLP collector is assumed

A `LoggingSpanExporter` bean is always registered, so `mvn spring-boot:run`
with zero extra configuration still prints spans — proof the feature works
without asking anyone to stand up Jaeger or Tempo first, consistent with
this project's zero-setup local-first default. Setting
`management.otlp.tracing.endpoint` points the same instrumentation at a real
collector; nothing in `MicrometerObservability` or the application layer
needs to change to do that, since Spring Boot's tracing auto-configuration
composes every `SpanExporter` bean present.

### 7. Structured JSON logs are explicitly deferred, not attempted half-way

Spring Boot's built-in `logging.structured.format` support arrived in 3.4;
this project's parent POM is pinned to 3.3.5. Doing this properly means
adding `logstash-logback-encoder` and a hand-written `logback-spring.xml`
that
injects the trace/span IDs Micrometer Tracing already puts in the MDC. That
is a self-contained enough piece of work to deserve its own pass rather than
a rushed version bolted onto this one — `docs/roadmap.md` marks FR-029
"(partially completed)" rather than claiming a sub-requirement that wasn't
built, following the precedent FR-027 already set for its own GitHub-only
scope cut.

## Consequences

- Prometheus metrics (`/actuator/prometheus`) and health/readiness
  (`/actuator/health/{liveness,readiness}`) work with zero external
  infrastructure, matching this project's local-first default; distributed
  trace *viewing* (as opposed to trace *creation*, which always happens)
  requires either reading the log or configuring a real OTLP endpoint.
- Per-step spans do not currently record errors for every failure path —
  only the phase-level spans (`analyse`/`execute`/`publish`) and a couple of
  validation-specific cases do. Duration and existence are still captured
  for every step either way; adding uniform per-step error tagging is a
  small, easy follow-up if traces prove hard to read without it.
- `contractguard.llm.tokens` is a `DistributionSummary`, not a `Counter` —
  chosen so the reported metric doubles as the mean/percentile token cost
  per prompt, not just a running total, at the cost of one extra `type` tag
  (`prompt` vs. `completion`) per prompt name.
